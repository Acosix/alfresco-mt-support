/*
 * Copyright 2016 Acosix GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.acosix.alfresco.mtsupport.repo.auth.ldap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class SidAttributeValueMapper implements AttributeValueMapper
{

    /**
     * {@inheritDoc}
     */
    @Override
    public Object mapAttributeValue(final String attributeId, final Object attributeValue)
    {
        Object result = attributeValue;
        if (attributeValue instanceof byte[])
        {
            final StringBuilder sb = new StringBuilder();
            final byte[] bytes = (byte[]) attributeValue;
            sb.append("S-").append(bytes[0] & 0xff); // revision
            final int subAuthorities = bytes[1] & 0xff; // count of sub authority identifiers in data
            sb.append("-").append(ByteBuffer.wrap(bytes).getLong() & 0xffffffffffffL); // authority
            final int[] subAuthorityParts = new int[subAuthorities];
            ByteBuffer.wrap(bytes, 8, bytes.length - 8).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(subAuthorityParts);
            for (final int subAuthorityPart : subAuthorityParts)
            {
                sb.append('-').append(subAuthorityPart & 0xffffffffL);
            }
            result = sb.toString();
        }
        return result;
    }

}
