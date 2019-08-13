/**
 * Copyright 2016-2019 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.oauth.internal.stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.reaktivity.nukleus.oauth.internal.util.BufferUtil.indexOfBytes;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.lang.JoseException;
import org.reaktivity.nukleus.concurrent.SignalingExecutor;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.oauth.internal.OAuthConfiguration;
import org.reaktivity.nukleus.oauth.internal.types.HttpHeaderFW;
import org.reaktivity.nukleus.oauth.internal.types.OctetsFW;
import org.reaktivity.nukleus.oauth.internal.types.String16FW;
import org.reaktivity.nukleus.oauth.internal.types.control.RouteFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.DataFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.EndFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.OAuthBeginExFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.OAuthSignalExFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.OAuthWindowExFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.SignalFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.oauth.internal.util.BufferUtil;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;
import org.reaktivity.specification.nukleus.oauth.internal.OAuthFunctions;

public class OAuthProxyFactory implements StreamFactory
{
    private static final long EXPIRES_NEVER = Long.MAX_VALUE;
    private static final long EXPIRES_IMMEDIATELY = 0L;

    private static final int TOKEN_CHALLENGE_SIGNAL = 1;

    // anb: possible alternatives - nbuff, buf, anbuf
    private static final String CHALLENGE_AFTER_CLAIM = "caf";

    private static final long REALM_MASK = 0xFFFF_000000000000L;

    private static final int SCOPE_BITS = 48;

    private static final Pattern QUERY_PARAMS = Pattern.compile("(?:\\?|.*?&)access_token=([^&#]+)(?:&.*)?");

    private static final byte[] BEARER_PREFIX = "Bearer ".getBytes(US_ASCII);
    private static final byte[] QUERY_PREFIX = "?".getBytes(US_ASCII);
    private static final byte[] AUTHORIZATION = "authorization".getBytes(US_ASCII);
    private static final byte[] PATH = ":path".getBytes(US_ASCII);

    private final RouteFW routeRO = new RouteFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();

    private final OctetsFW octetsRO = new OctetsFW().wrap(new UnsafeBuffer(new byte[0]), 0, 0);

    private final HttpBeginExFW httpBeginExRO = new HttpBeginExFW();
    private final HttpBeginExFW.Builder httpBeginExRW = new HttpBeginExFW.Builder();

    private final OAuthBeginExFW beginExRO = new OAuthBeginExFW();
    private final OAuthWindowExFW windowExRO = new OAuthWindowExFW();
//    private final OAuthSignalExFW signalExRO = new OAuthSignalExFW();

    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();
    private final AbortFW abortRO = new AbortFW();
    private final SignalFW signalRO = new SignalFW();

    private final JsonWebSignature signature = new JsonWebSignature();

    private final Long2ObjectHashMap<Map<String, OAuthAccessGrant>>[] grantsBySubjectByAffinityPerRealm;

    private final OAuthConfiguration config;
    private final RouteManager router;
    private final LongUnaryOperator supplyInitialId;
    private final LongSupplier supplyTrace;
    private final LongUnaryOperator supplyReplyId;
    private final Function<String, JsonWebKey> lookupKey;
    private final ToLongFunction<JsonWebSignature> lookupAuthorization;
    private final SignalingExecutor executor;
    private final Long2ObjectHashMap<OAuthProxy> correlations;

    private final Writer writer;
    private final UnsafeBuffer extensionBuffer;
    private final int httpTypeId;

    public OAuthProxyFactory(
        OAuthConfiguration config,
        MutableDirectBuffer writeBuffer,
        LongUnaryOperator supplyInitialId,
        LongSupplier supplyTrace,
        ToIntFunction<String> supplyTypeId,
        LongUnaryOperator supplyReplyId,
        Long2ObjectHashMap<OAuthProxy> correlations,
        Function<String, JsonWebKey> lookupKey,
        ToLongFunction<JsonWebSignature> lookupAuthorization,
        SignalingExecutor executor,
        RouteManager router)
    {
        this.config = config;
        this.router = requireNonNull(router);
        this.writer = new Writer(writeBuffer);
        this.extensionBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.supplyInitialId = requireNonNull(supplyInitialId);
        this.supplyReplyId = requireNonNull(supplyReplyId);
        this.supplyTrace = requireNonNull(supplyTrace);
        this.correlations = correlations;
        this.lookupKey = lookupKey;
        this.lookupAuthorization = lookupAuthorization;
        this.executor = executor;
        this.httpTypeId = supplyTypeId.applyAsInt("http");
        this.grantsBySubjectByAffinityPerRealm = new Long2ObjectHashMap[16];
        Arrays.setAll(grantsBySubjectByAffinityPerRealm, i -> new Long2ObjectHashMap<>());
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer source)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long streamId = begin.streamId();

        MessageConsumer newStream;

        if ((streamId & 0x0000_0000_0000_0001L) != 0L)
        {
            newStream = newInitialStream(begin, source);
        }
        else
        {
            newStream = newReplyStream(begin, source);
        }

        return newStream;
    }

    private MessageConsumer newInitialStream(
        final BeginFW begin,
        final MessageConsumer acceptReply)
    {
//        System.out.println("newInitialStream - BEGIN: " + begin);
        final long acceptAuthorization = begin.authorization();
        final JsonWebSignature verified = verifiedSignature(begin);

        long connectAuthorization = acceptAuthorization;
        if (verified != null)
        {
            connectAuthorization = lookupAuthorization.applyAsLong(verified);
        }

        final long acceptRouteId = begin.routeId();
        final MessagePredicate filter = (t, b, o, l) -> true;
        final RouteFW route = router.resolve(acceptRouteId, connectAuthorization, filter, this::wrapRoute);
        MessageConsumer newStream = null;

        if (route != null)
        {
            final long acceptInitialId = begin.streamId();
            final long traceId = begin.trace();
            final long affinity = begin.affinity();
            final OctetsFW extension = begin.extension();

            long acceptReplyId = supplyReplyId.applyAsLong(acceptInitialId);
            long connectRouteId = route.correlationId();
            long connectInitialId = supplyInitialId.applyAsLong(connectRouteId);
            MessageConsumer connectInitial = router.supplyReceiver(connectInitialId);
            long connectReplyId = supplyReplyId.applyAsLong(connectInitialId);
            long expiresAtMillis = config.expireInFlightRequests() ? expiresAtMillis(verified) : EXPIRES_NEVER;

            final String subject = resolveSubject(verified);
            final int realmId = (int) ((connectAuthorization & REALM_MASK) >> SCOPE_BITS);

            // TODO: get nbuff/anb claim: advanced notification buffer for expiration
            //       possibly store in grant as well: schedule an advancedExpiryNotificationFuture that would trigger
            //          trying to send a challenge upstream: includes checking list of streams that support CHALLENGE
            //          also checks to make sure original stream isnt closed, else check if already reauthorized, else
            //          need to choose a different stream. else will expire as normal

            // TODO: create OAuthBeginEx from begin.extension() to see if valid extension that supports CHALLENGE
            //       if so, then add stream to list. what adding here: correlationId???
            //          - difference between declaring support in BEGIN vs claim?
            //              - claim is token specific: NUKLEUS STILL MAY NOT SUPPORT CHALLENGE
            //              - this is why we need to declare support in BEGIN as it comes from that upstream nukleus

            final boolean streamSupportsChallenge = resolveChallengeSupport(begin);
            final long challengeDelta = resolveAdvancedNotificationBuffer(verified, streamSupportsChallenge, expiresAtMillis);
            final OAuthAccessGrant grant = supplyGrant(realmId, affinity, subject);
            grant.reauthorize(subject, connectAuthorization, expiresAtMillis);

            OAuthProxy initialStream = new OAuthProxy(acceptReply, acceptRouteId, acceptInitialId, acceptAuthorization,
                    connectInitial, connectRouteId, connectInitialId, connectAuthorization,
                    acceptInitialId, connectReplyId, expiresAtMillis, 0, grant);
            initialStream.grant.acquire();

            OAuthProxy replyStream = new OAuthProxy(connectInitial, connectRouteId, connectReplyId, connectAuthorization,
                    acceptReply, acceptRouteId, acceptReplyId, acceptAuthorization,
                    acceptInitialId, connectReplyId, expiresAtMillis, challengeDelta, grant);
            replyStream.grant.acquire();

            // if stream supports challenge, add to list for future reference
//            if (streamSupportsChallenge)
//            {
////                challengeCapableStreams.put(connectReplyId, acceptReply);
//                challengeCapableStreams.put(connectInitialId, connectInitial);
//            }

            correlations.put(connectReplyId, replyStream);
            router.setThrottle(acceptReplyId, replyStream::onThrottleMessage);

            writer.doBegin(connectInitial, connectRouteId, connectInitialId, traceId,
                    connectAuthorization, extension);
            router.setThrottle(connectInitialId, initialStream::onThrottleMessage);

//            System.out.println("initialStream.sourceStreamId: " + initialStream.sourceStreamId);
            newStream = initialStream::onStreamMessage;
        }

        return newStream;
    }

    private MessageConsumer newReplyStream(
        final BeginFW begin,
        final MessageConsumer sender)
    {
        final long connectReplyId = begin.streamId();
        final long traceId = begin.trace();
        final long authorization = begin.authorization();
        final OctetsFW extension = begin.extension();

        OAuthProxy replyStream = correlations.remove(connectReplyId);

        MessageConsumer newStream = null;

        if (replyStream != null)
        {
            MessageConsumer acceptReply = replyStream.target;
            long acceptRouteId = replyStream.targetRouteId;
            long acceptReplyId = replyStream.targetStreamId;

            writer.doBegin(acceptReply, acceptRouteId, acceptReplyId, traceId, authorization, extension);

            newStream = replyStream::onStreamMessage;
        }

        return newStream;
    }

    private RouteFW wrapRoute(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        return routeRO.wrap(buffer, index, index + length);
    }

    private String resolveSubject(
        JsonWebSignature verified)
    {
        String subject = null;
        try
        {
            if (verified != null)
            {
                final JwtClaims claims = JwtClaims.parse(verified.getPayload());
                subject = claims.getSubject();
            }
        }
        catch (InvalidJwtException | JoseException | MalformedClaimException e)
        {
            // TODO: diagnostics?
        }
        return subject;
    }

    // Challenge after - caf
    private long resolveAdvancedNotificationBuffer(
        JsonWebSignature verified,
        boolean streamSupportsChallenge,
        long expiresAtMillis)
    {
        long bufferDelta = 0;

        if (streamSupportsChallenge)
        {
            try
            {
                if (verified != null)
                {
                    final JwtClaims claims = JwtClaims.parse(verified.getPayload());
                    final NumericDate buffer = claims.getNumericDateClaimValue(CHALLENGE_AFTER_CLAIM);
                    if (buffer != null)
                    {
                        bufferDelta = expiresAtMillis - buffer.getValueInMillis();
                    }
                }
            }
            catch (InvalidJwtException | JoseException | MalformedClaimException e)
            {
                // TODO: diagnostics?
            }
        }
        return bufferDelta;
    }

    private boolean resolveChallengeSupport(
        BeginFW begin)
    {
        boolean supportsChallenge = false;
//        System.out.println("begin.extension().limit() vs. beginExRO.limit(): \t" + begin.extension().sizeof() +
//                           " :: " + beginExRO.limit());
        if (begin.extension().sizeof() > 0 && begin.extension().sizeof() == beginExRO.limit())
        {
            final OAuthBeginExFW beginEx = beginExRO.tryWrap(begin.buffer(),
                                                             begin.extension().offset(),
                                                             begin.extension().limit());
//            System.out.println("extension was valid: beginEx - " + beginEx);
            if (beginEx != null)
            {
                supportsChallenge = beginEx.supportsChallenge() == 1;
            }
        }
        return supportsChallenge;
    }

    private OAuthAccessGrant supplyGrant(
        final int realmIndex,
        final long affinityId,
        final String subject)
    {
        final Long2ObjectHashMap<Map<String, OAuthAccessGrant>> grantsBySubjectByAffinity =
                grantsBySubjectByAffinityPerRealm[realmIndex];
        final Map<String, OAuthAccessGrant> grantsBySubject =
                grantsBySubjectByAffinity.computeIfAbsent(affinityId, a -> new IdentityHashMap<>());

        if (subject != null)
        {
            final String subjectKey = subject.intern();
            return grantsBySubject.computeIfAbsent(subjectKey, s -> new OAuthAccessGrant(grantsBySubject::remove));
        }
        else
        {
            return new OAuthAccessGrant(this::noOp);
        }
    }

    private void noOp(
        String subject)
    {
    }

    private static long expiresAtMillis(
        JsonWebSignature verified)
    {
        long expiresAtMillis = EXPIRES_NEVER;

        if (verified != null)
        {
            try
            {
                JwtClaims claims = JwtClaims.parse(verified.getPayload());
                NumericDate expirationTime = claims.getExpirationTime();
                if (expirationTime != null)
                {
                    expiresAtMillis = expirationTime.getValueInMillis();
                }
            }
            catch (MalformedClaimException | InvalidJwtException | JoseException ex)
            {
                expiresAtMillis = EXPIRES_IMMEDIATELY;
            }
        }

        return expiresAtMillis;
    }

    // TODO: Maybe grants can also hold a variable to tell if its stream supports challenges
    private final class OAuthAccessGrant
    {
        private String subject;
        private long authorization;
        private long expiresAt;
        private int referenceCount;
        private Consumer<String> cleaner;

        private OAuthAccessGrant(
            Consumer<String> cleaner)
        {
            this.cleaner = cleaner;
        }

        private boolean reauthorize(
            String subject,
            long connectAuthorization,
            long expiresAtMillis)
        {
            final boolean reauthorized;
            if (referenceCount > 0)
            {
                final long grantAuthorization = authorization;
                reauthorized = (grantAuthorization & connectAuthorization) == grantAuthorization && expiresAtMillis > expiresAt;

                if (reauthorized)
                {
                    this.expiresAt = expiresAtMillis;
                }
            }
            else
            {
                this.subject = subject != null ? subject.intern() : null;
                this.authorization = connectAuthorization;
                this.expiresAt = expiresAtMillis;
                reauthorized = false;
            }
            return reauthorized;
        }

        private void acquire()
        {
            assert (cleaner != null);
            referenceCount++;
        }

        private void release()
        {
            assert (cleaner != null && referenceCount > 0);
            referenceCount--;
            if (referenceCount == 0)
            {
                if (subject != null)
                {
                    cleaner.accept(subject.intern());
                }
                cleaner = null;
            }
        }

        @Override
        public String toString()
        {
            return String.format("OAuthAccessGrant=[subject=%s, authorization=%d, expiresAt=%d, referenceCount=%d]",
                                 subject, authorization, expiresAt, referenceCount);
        }
    }

    final class OAuthProxy
    {
        private final MessageConsumer source;
        private final long sourceRouteId;
        private final long sourceStreamId;
        private final long sourceAuthorization;
        private final MessageConsumer target;
        private final long targetRouteId;
        private final long targetStreamId;
        private final long targetAuthorization;
        private final long acceptInitialId;
        private final long connectReplyId;
        private final long challengeDelta;

        private final OAuthAccessGrant grant;

        private Future<?> expiryFuture;

        private OAuthProxy(
            MessageConsumer source,
            long sourceRouteId,
            long sourceId,
            long sourceAuthorization,
            MessageConsumer target,
            long targetRouteId,
            long targetId,
            long targetAuthorization,
            long acceptInitialId,
            long connectReplyId,
            long expiresAtMillis,
            long challengeDelta,
            OAuthAccessGrant grant)
        {
            this.source = source;
            this.sourceRouteId = sourceRouteId;
            this.sourceStreamId = sourceId;
            this.sourceAuthorization = sourceAuthorization;
            this.target = target;
            this.targetRouteId = targetRouteId;
            this.targetStreamId = targetId;
            this.targetAuthorization = targetAuthorization;
            this.acceptInitialId = acceptInitialId;
            this.connectReplyId = connectReplyId;
            this.grant = Objects.requireNonNull(grant);
            this.challengeDelta = challengeDelta;

            final long delay;
            if (expiresAtMillis != EXPIRES_NEVER && challengeDelta == 0)
            {
                delay = expiresAtMillis - System.currentTimeMillis();

                this.expiryFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId, TOKEN_CHALLENGE_SIGNAL);
            }
            else if (challengeDelta > 0)
            {
                delay = expiresAtMillis - challengeDelta;

                this.expiryFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId, TOKEN_CHALLENGE_SIGNAL);
            }
        }

        private void onStreamMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onAbort(abort);
                break;
            default:
                writer.doReset(source, sourceRouteId, sourceStreamId, supplyTrace.getAsLong(), sourceAuthorization);
                break;
            }
        }

        private void onThrottleMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onReset(reset);
                break;
            case SignalFW.TYPE_ID:
                final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                onSignal(signal);
                break;
            default:
                // ignore
                break;
            }
        }

        private void onBegin(
            BeginFW begin)
        {
        }

        private void onData(
            DataFW data)
        {
            final long traceId = data.trace();
            final int padding = data.padding();
            final long authorization = data.authorization();
            final long groupId = data.groupId();
            final OctetsFW payload = data.payload();
            final OctetsFW extension = data.extension();

            writer.doData(target, targetRouteId, targetStreamId, traceId,
                          authorization, groupId, padding, payload, extension);

        }

        private void onEnd(
            EndFW end)
        {
            final long traceId = end.trace();
            final OctetsFW extension = end.extension();

            writer.doEnd(target, targetRouteId, targetStreamId, traceId, targetAuthorization, extension);

            cancelTimerIfNecessary();
        }

        private void onAbort(
            AbortFW abort)
        {
            final long traceId = abort.trace();

            writer.doAbort(target, targetRouteId, targetStreamId, traceId, targetAuthorization);

            cleanupCorrelationIfNecessary();
            cancelTimerIfNecessary();
        }

        private void onWindow(
            WindowFW window)
        {
            final int credit = window.credit();
            final long traceId = window.trace();
            final int padding = window.padding();
            final long groupId = window.groupId();

//            System.out.println("onWindow() - " + window);
//            System.out.println("window.extension().limit() vs. windowExRO.limit(): \t" + window.extension().sizeof() +
//                    " :: " + windowExRO.limit());
//            boolean supportsChallenge = false;
//            OAuthWindowExFW windowEx = null;
//            if (window.extension().sizeof() > 0 && window.extension().limit() == windowExRO.limit())
//            {
//                windowEx = windowExRO.tryWrap(window.buffer(),
//                                              window.extension().offset(),
//                                              window.extension().limit());
////                System.out.println("extension was valid: windowEx - " + windowEx);
//                if (windowEx != null)
//                {
//                    supportsChallenge = windowEx.supportsChallenge() == 1;
//                }
//            }

            // send 200 OK to client
            // how would sse know to relay to client? would windowEx from oauth trigger that?
            //      as signalEx should trigger the challenge event in the client
//            if (supportsChallenge)
//            {
//                writer.doWindow(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization, credit, padding, groupId,
//                        windowEx);
//            }
//            else
//            {
//                writer.doWindow(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization, credit, padding, groupId);
//            }
            writer.doWindow(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization, credit, padding, groupId);
        }

        private void onReset(
            ResetFW reset)
        {
            final long traceId = reset.trace();

            writer.doReset(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization);

            cleanupCorrelationIfNecessary();
            cancelTimerIfNecessary();
        }

        // How would know when challenge response is not received?
        //      maybe token expired signal is triggered?
        //          but maybe its too late at that point?
        //          maybe schedule another future for another signal in case we want to expect a timeout
        private void onSignal(
            SignalFW signal)
        {
            final long signalId = signal.signalId();

            switch ((int) signalId)
            {
                case TOKEN_CHALLENGE_SIGNAL:
                    onTokenExpiredSignal(signal);
                    break;
                default:
                    break;
            }
        }

        private void onTokenExpiredSignal(
            SignalFW signal)
        {
            final long delay = grant.expiresAt - System.currentTimeMillis();

            if (delay >= 0)
            {
                final long challengeAfter = grant.expiresAt - challengeDelta;

                // TODO: check if inside buffer, if earlier, then reschedule the buffer check
                if (withinChallengeBuffer(System.currentTimeMillis(), challengeAfter))  // not reauthorized and inside buffer
                {
                    onTokenExpiringSoonSignal(signal);
                }
                else if (System.currentTimeMillis() < challengeAfter)   // reauthorized and is outside (before) the buffer
                {
                    this.expiryFuture = executor.schedule(challengeAfter, MILLISECONDS, targetRouteId, targetStreamId,
                            TOKEN_CHALLENGE_SIGNAL);
                }
                else    // was expiring but then was reauthorized
                {
                    this.expiryFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId,
                            TOKEN_CHALLENGE_SIGNAL);
                }
            }
            else
            {
                final long traceId = signal.trace();
                writer.doReset(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization);

                final boolean replyNotStarted = cleanupCorrelationIfNecessary();

                if (sourceStreamId == connectReplyId && replyNotStarted)
                {
                    final HttpBeginExFW httpBeginEx = httpBeginExRW.wrap(extensionBuffer, 0, extensionBuffer.capacity())
                                                                   .typeId(httpTypeId)
                                                                   .headersItem(h -> h.name(":status").value("401"))
                                                                   .build();

                    writer.doBegin(target, targetRouteId, targetStreamId, traceId, targetAuthorization, httpBeginEx);
                    writer.doEnd(target, targetRouteId, targetStreamId, traceId, targetAuthorization, octetsRO);
                }
                else
                {
                    writer.doAbort(target, targetRouteId, targetStreamId, traceId, targetAuthorization);
                }

                grant.release();
            }
        }

        private boolean withinChallengeBuffer(
            long currentTimeMillis,
            long challengeAfter)
        {
            return currentTimeMillis >= challengeAfter && currentTimeMillis < grant.expiresAt;
        }

        // COULD MOVE TO SAME SIGNAL ID; GRANT_VALIDATION_SIGNAL = 1;
        private void onTokenExpiringSoonSignal(
            SignalFW signal)
        {
            // TODO: writer.doFrame(target, ...) - this will let oauth write a frame to a specific target which in this
            //       case will be a stream that isn't expire and supports challenges
            final long finalDelay = grant.expiresAt - System.currentTimeMillis();
            this.expiryFuture = executor.schedule(finalDelay, MILLISECONDS, targetRouteId, targetStreamId, TOKEN_CHALLENGE_SIGNAL);

            // base64 correlation?
            // TODO: some sort of challenge payload would be nice here
            byte[] build = OAuthFunctions.signalEx()
                    .challenge("{ \":method\":\"post\", \"headers\": { \"correlation\": \"" +
                            connectReplyId + "\" } }")
                    .build();

            extensionBuffer.wrap(build);

            final OAuthSignalExFW oauthSignalEx = new OAuthSignalExFW().wrap(extensionBuffer, 0,
                    extensionBuffer.capacity());
            final long traceId = signal.trace();

            writer.doSignal(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization, oauthSignalEx);
        }

        private boolean cleanupCorrelationIfNecessary()
        {
            final OAuthProxy correlated = correlations.remove(connectReplyId);
            if (correlated != null)
            {
                router.clearThrottle(acceptInitialId);
            }

            return correlated != null;
        }

        private void cancelTimerIfNecessary()
        {
            if (expiryFuture != null)
            {
                expiryFuture.cancel(true);
                expiryFuture = null;
                grant.release();
            }
        }

        @Override
        public String toString()
        {
            return String.format("OAuthProxy - {sourceRouteId=%d, sourceStreamId=%d, sourceAuthorization=%d, targetRouteId=%d, " +
                    "targetStreamId=%d, targetAuthorization=%d, acceptInitialId=%d, connectReplyId=%d}",
                    sourceRouteId, sourceStreamId, sourceAuthorization, targetRouteId, targetStreamId, targetAuthorization,
                    acceptInitialId, connectReplyId);
        }
    }

    private JsonWebSignature verifiedSignature(
        BeginFW begin)
    {
        JsonWebSignature verified = null;

        final String token = bearerToken(begin);
        if (token != null)
        {
            try
            {
                signature.setCompactSerialization(token);
                final String kid = signature.getKeyIdHeaderValue();
                final String algorithm = signature.getAlgorithmHeaderValue();
                final JsonWebKey key = lookupKey.apply(kid);
                if (algorithm != null && key != null && algorithm.equals(key.getAlgorithm()))
                {
                    signature.setKey(null);
                    signature.setKey(key.getKey());

                    final JwtClaims claims = JwtClaims.parse(signature.getPayload());
                    final NumericDate expirationTime = claims.getExpirationTime();
                    final NumericDate notBefore = claims.getNotBefore();
                    final long now = System.currentTimeMillis();
                    if ((expirationTime == null || now <= expirationTime.getValueInMillis()) &&
                        (notBefore == null || now >= notBefore.getValueInMillis()) &&
                        signature.verifySignature())
                    {
                        verified = signature;
                    }
                }
            }
            catch (JoseException | MalformedClaimException | InvalidJwtException ex)
            {
                // TODO: diagnostics?
            }
        }

        return verified;
    }

    private String bearerToken(
        BeginFW begin)
    {
        String token = null;

        final HttpBeginExFW beginEx = begin.extension().get(httpBeginExRO::tryWrap);
        if (beginEx != null)
        {
            final HttpHeaderFW path = beginEx.headers().matchFirst(h -> BufferUtil.equals(h.name(), PATH));
            if (path != null)
            {
                final String16FW value = path.value();
                final int queryAt = indexOfBytes(value, QUERY_PREFIX);
                if (queryAt != -1)
                {
                    final String query = path.value().asString().substring(queryAt);
                    final Matcher matcher = QUERY_PARAMS.matcher(query);
                    if (matcher.matches())
                    {
                        token = matcher.group(1);
                    }
                }
            }

            final HttpHeaderFW authorization = beginEx.headers().matchFirst(h -> BufferUtil.equals(h.name(), AUTHORIZATION));
            if (authorization != null)
            {
                final String16FW value = authorization.value();

                final int tokenAt = BufferUtil.limitOfBytes(value, BEARER_PREFIX);

                if (tokenAt > 0)
                {
                    final DirectBuffer buffer = value.buffer();
                    final int limit = value.limit();
                    token = buffer.getStringWithoutLengthUtf8(tokenAt, limit - tokenAt);
                }
            }
        }

        return token;
    }
}
