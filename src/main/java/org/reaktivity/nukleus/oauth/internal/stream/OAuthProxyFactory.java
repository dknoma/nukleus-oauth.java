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
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.CHALLENGE_DELTA_CLAIM_NAME;
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
import org.reaktivity.nukleus.oauth.internal.Capabilities;
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
import org.reaktivity.nukleus.oauth.internal.types.stream.HttpSignalExFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.SignalFW;
import org.reaktivity.nukleus.oauth.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.oauth.internal.util.BufferUtil;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public class OAuthProxyFactory implements StreamFactory
{
    private static final long EXPIRES_NEVER = Long.MAX_VALUE;
    private static final long EXPIRES_IMMEDIATELY = 0L;

    private static final int GRANT_VALIDATION_SIGNAL = 1;

    private static final long REALM_MASK = 0xFFFF_000000000000L;

    private static final int SCOPE_BITS = 48;

    private static final Consumer<String> NOOP_CLEANER = s -> {};

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

    private final HttpSignalExFW.Builder httpSignalExRW = new HttpSignalExFW.Builder();

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

        // TODO NOW - possibly make changes to core.idl,
        //  ~            - capabilities uint8 bits; looks like authorization
        //  ~            - ${core:capabilities("challenge")} to write the correct bits to capabilities
        //          - read/write signal in k3po
        //          - SseEventFW
        //                      .type = "challenge"
        //                      .payload = "{}"
        //  ~        - Window capabilities will set the capabilities of the streams
        // TODO LATER: get HttpBeginEx to check headers to check if reauthorization was triggered by me.
        //  ~        - content-type application|x-challenge-response
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

            final int capabilities = begin.capabilities();
            final long challengeDelta = resolveChallengeDelta(verified, capabilities, expiresAtMillis);
            final OAuthAccessGrant grant = supplyGrant(realmId, affinity, subject);
            grant.reauthorize(subject, connectAuthorization, expiresAtMillis, challengeDelta);

            OAuthProxy initialStream = new OAuthProxy(acceptReply, acceptRouteId, acceptInitialId, acceptAuthorization,
                    connectInitial, connectRouteId, connectInitialId, connectAuthorization,
                    acceptInitialId, connectReplyId, expiresAtMillis, 0, capabilities, grant);
            initialStream.grant.acquire();

            OAuthProxy replyStream = new OAuthProxy(connectInitial, connectRouteId, connectReplyId, connectAuthorization,
                    acceptReply, acceptRouteId, acceptReplyId, acceptAuthorization,
                    acceptInitialId, connectReplyId, expiresAtMillis, challengeDelta, capabilities, grant);
            replyStream.grant.acquire();

            correlations.put(connectReplyId, replyStream);
            router.setThrottle(acceptReplyId, replyStream::onThrottleMessage);

            writer.doBegin(connectInitial, connectRouteId, connectInitialId, traceId,
                    connectAuthorization, extension);
            router.setThrottle(connectInitialId, initialStream::onThrottleMessage);

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
            // invalid token
        }
        return subject;
    }

    private long resolveChallengeDelta(
        JsonWebSignature verified,
        int capabilities,
        long expiresAtMillis)
    {
        long challengeDelta = 0;

        if (Capabilities.canChallenge(capabilities))
        {
            try
            {
                if (verified != null)
                {
                    final JwtClaims claims = JwtClaims.parse(verified.getPayload());
                    final NumericDate challengeAfterDate = claims.getNumericDateClaimValue(
                            config.getChallengeDeltaClaimNamespace() + CHALLENGE_DELTA_CLAIM_NAME);
                    if (challengeAfterDate != null)
                    {
                        challengeDelta = expiresAtMillis - challengeAfterDate.getValueInMillis();
                    }
                }
            }
            catch (InvalidJwtException | JoseException | MalformedClaimException e)
            {
                // invalid token
            }
        }
        return challengeDelta;
    }

    private OAuthAccessGrant supplyGrant(
        final int realmIndex,
        final long affinityId,
        final String subject)
    {
        if (subject != null)
        {
            final Map<String, OAuthAccessGrant> grantsBySubject = supplyGrantsBySubject(realmIndex, affinityId);
            final String subjectKey = subject.intern();
            return grantsBySubject.computeIfAbsent(subjectKey, s -> new OAuthAccessGrant(grantsBySubject::remove));
        }
        else
        {
            return new OAuthAccessGrant();
        }
    }

    private Map<String, OAuthAccessGrant> supplyGrantsBySubject(
        final int realmIndex,
        final long affinityId)
    {
        final Long2ObjectHashMap<Map<String, OAuthAccessGrant>> grantsBySubjectByAffinity =
                grantsBySubjectByAffinityPerRealm[realmIndex];
        return grantsBySubjectByAffinity.computeIfAbsent(affinityId, a -> new IdentityHashMap<>());
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

    private final class OAuthAccessGrant
    {
        private String subject;
        private long authorization;
        private long expiresAt;
        private long challengeDelta;
        private int referenceCount;
        private Consumer<String> cleaner;

        private OAuthAccessGrant(
            Consumer<String> cleaner)
        {
            this.cleaner = cleaner;
        }

        private OAuthAccessGrant()
        {
            this.cleaner = NOOP_CLEANER;
        }

        private boolean reauthorize(
            String subject,
            long connectAuthorization,
            long expiresAtMillis,
            long challengeDelta)
        {
            final boolean reauthorized;
            if (referenceCount > 0)
            {
                final long grantAuthorization = authorization;
                reauthorized = (grantAuthorization & connectAuthorization) == grantAuthorization && expiresAtMillis > expiresAt;

                if (reauthorized)
                {
                    this.expiresAt = expiresAtMillis;
                    this.challengeDelta = challengeDelta;
                }
            }
            else
            {
                this.subject = subject != null ? subject.intern() : null;
                this.authorization = connectAuthorization;
                this.expiresAt = expiresAtMillis;
                this.challengeDelta = challengeDelta;
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
                    cleaner.accept(subject);
                }
                cleaner = null;
            }
        }

        private long challengeAfter()
        {
            return this.expiresAt - this.challengeDelta;
        }

        @Override
        public String toString()
        {
            return String.format("OAuthAccessGrant=[subject=%s, authorization=%d, expiresAt=%d, challengeDelta=%d, referenceCount=%d]",
                                 subject, authorization, expiresAt, challengeDelta, referenceCount);
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

        private final OAuthAccessGrant grant;

        private int capabilities;

        private Future<?> grantValidationFuture;

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
            int capabilities,
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
            this.capabilities = capabilities;

            final boolean canChallenge = Capabilities.canChallenge(capabilities);
            final long delay;
            if (canChallenge && challengeDelta > 0)
            {
                delay = expiresAtMillis - challengeDelta;

                this.grantValidationFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId,
                        GRANT_VALIDATION_SIGNAL);
            }
            else if (expiresAtMillis != EXPIRES_NEVER)
            {
                delay = expiresAtMillis - System.currentTimeMillis();

                this.grantValidationFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId,
                        GRANT_VALIDATION_SIGNAL);
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

            // whatever capabilities you get, set this streams capabilities to that
            this.capabilities = window.capabilities();

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

        private void onSignal(
            SignalFW signal)
        {
            final long signalId = signal.signalId();

            switch ((int) signalId)
            {
                case GRANT_VALIDATION_SIGNAL:
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

            if (delay > 0)
            {
                final long challengeAfter = grant.challengeAfter();
                final boolean hasChallengeCapability = Capabilities.canChallenge(capabilities);

                if (hasChallengeCapability)
                {
                    if (withinChallengeBuffer(System.currentTimeMillis(), challengeAfter))
                    {
//                        System.out.println("issuing challenge request to client");
                        onTokenExpiringSoonSignal(signal);
                    }
                    else if (System.currentTimeMillis() < challengeAfter)
                    {
//                        System.out.println("rescheduling advanced notificaiton");
                        this.grantValidationFuture = executor.schedule(challengeAfter, MILLISECONDS, targetRouteId, targetStreamId,
                                GRANT_VALIDATION_SIGNAL);
                    }
                    else
                    {
//                        System.out.println("extending expiration");
                        this.grantValidationFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId,
                                GRANT_VALIDATION_SIGNAL);
                    }
                }
                else
                {
//                    System.out.println("extending expiration");
                    this.grantValidationFuture = executor.schedule(delay, MILLISECONDS, targetRouteId, targetStreamId,
                            GRANT_VALIDATION_SIGNAL);
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

        // TODO: when receive begin, check headers and see if have application/x-challenge-response, then can terminate challenge!
        private void onTokenExpiringSoonSignal(
            SignalFW signal)
        {
            final long finalDelay = grant.expiresAt - System.currentTimeMillis();
            this.grantValidationFuture = executor.schedule(finalDelay, MILLISECONDS, targetRouteId, targetStreamId,
                    GRANT_VALIDATION_SIGNAL);

            final HttpSignalExFW httpSignalEx = httpSignalExRW.wrap(extensionBuffer, 0, extensionBuffer.capacity())
                    .typeId(httpTypeId)
                    .headersItem(h -> h.name(":method").value("post"))
                    .headersItem(h -> h.name("content-type").value("application/x-challenge-response"))
                    .build();
            final long traceId = signal.trace();

            writer.doSignal(source, sourceRouteId, sourceStreamId, traceId, sourceAuthorization, httpSignalEx);
        }

        private boolean withinChallengeBuffer(
                long currentTimeMillis,
                long challengeAfter)
        {
            return currentTimeMillis >= challengeAfter && currentTimeMillis < grant.expiresAt;
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
            if (grantValidationFuture != null)
            {
                grantValidationFuture.cancel(true);
                grantValidationFuture = null;
                grant.release();
            }
        }

        @Override
        public String toString()
        {
            return String.format("OAuthProxy - {sourceRouteId=%d, sourceStreamId=%d, sourceAuthorization=%d, targetRouteId=%d, " +
                    "targetStreamId=%d, targetAuthorization=%d, acceptInitialId=%d, connectReplyId=%d, capabilities=%d, " +
                            "grant=%s}",
                    sourceRouteId, sourceStreamId, sourceAuthorization, targetRouteId, targetStreamId, targetAuthorization,
                    acceptInitialId, connectReplyId, capabilities, grant);
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
                // invalid token
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
