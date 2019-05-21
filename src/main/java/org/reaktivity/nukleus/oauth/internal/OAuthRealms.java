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
package org.reaktivity.nukleus.oauth.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableMap;
import static org.agrona.LangUtil.rethrowUnchecked;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.lang.JoseException;
import org.reaktivity.nukleus.internal.CopyOnWriteHashMap;

public class OAuthRealms
{
    private static final Long NO_AUTHORIZATION = 0L;

    // To optimize authorization checks we use a single distinct bit per realm
    private static final int MAX_REALMS = Short.SIZE;

    private static final long SCOPE_MASK = 0xFFFF_000000000000L;

    private final Map<String, Long> realmsIdsByName = new CopyOnWriteHashMap<>();

    private int nextRealmBitShift = 48;

    private final Map<String, JsonWebKey> keysByKid;

    public OAuthRealms()
    {
        this(Collections.emptyMap());
    }

    public OAuthRealms(
        Path keyFile)
    {
        this(parseKeyMap(keyFile));
    }

    public OAuthRealms(
        String keysAsJwkSet)
    {
        this(toKeyMap(keysAsJwkSet));
    }

    private OAuthRealms(
        Map<String, JsonWebKey> keysByKid)
    {
        keysByKid.forEach((k, v) -> add(v.getKeyId()));
        this.keysByKid = keysByKid;
    }

    public void add(
        String realm)
    {
        if (realmsIdsByName.size() == MAX_REALMS)
        {
            throw new IllegalStateException("Too many realms");
        }
        realmsIdsByName.put(realm, 1L << nextRealmBitShift++);
    }

    public long resolve(
        String realm)
    {
        return realmsIdsByName.getOrDefault(realm, NO_AUTHORIZATION);
    }

    public boolean unresolve(
        long authorization)
    {
        long scope = authorization & SCOPE_MASK;
        boolean result;
        if (Long.bitCount(scope) > 1)
        {
            result = false;
        }
        else
        {
            result = realmsIdsByName.entrySet().removeIf(e -> (e.getValue() == scope));
        }
        return result;
    }

    public JsonWebKey supplyKey(
        String kid)
    {
        return keysByKid.get(kid);
    }

    private static Map<String, JsonWebKey> parseKeyMap(
        Path keyFile)
    {
        Map<String, JsonWebKey> keysByKid = Collections.emptyMap();

        if (Files.exists(keyFile))
        {
            try
            {
                byte[] rawKeys = Files.readAllBytes(keyFile);
                String keysAsJwkSet = new String(rawKeys, UTF_8);
                keysByKid = toKeyMap(keysAsJwkSet);
            }
            catch (IOException ex)
            {
                rethrowUnchecked(ex);
            }
        }

        return keysByKid;
    }

    private static Map<String, JsonWebKey> toKeyMap(
        String keysAsJwkSet)
    {
        Map<String, JsonWebKey> keysByKid = Collections.emptyMap();

        try
        {
            JsonWebKeySet keys = new JsonWebKeySet(keysAsJwkSet);
            keysByKid = new LinkedHashMap<>();
            for (JsonWebKey key : keys.getJsonWebKeys())
            {
               String kid = key.getKeyId();
               if (kid == null)
               {
                   throw new IllegalArgumentException("Key without kid");
               }

               if (key.getAlgorithm() == null)
               {
                   throw new IllegalArgumentException("Key without alg");
               }

               final JsonWebKey existingKey = keysByKid.putIfAbsent(kid, key);
               if (existingKey != null)
               {
                   throw new IllegalArgumentException("Key with duplicate kid");
               }
            }
            keysByKid = unmodifiableMap(keysByKid);
        }
        catch (JoseException ex)
        {
            rethrowUnchecked(ex);
        }

        return keysByKid;
    }
}
