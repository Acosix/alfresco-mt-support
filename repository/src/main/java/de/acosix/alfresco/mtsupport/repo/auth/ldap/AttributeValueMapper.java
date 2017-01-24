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

/**
 * Instances of this interface provide means to map the attribute value of specific LDAP attributes to common Java value types that may be
 * used as Alfresco node properties for people or groups. Typically attribute value mappers are actively registered with a
 * {@link EnhancedLDAPUserRegistry} for specific attributes, but may technically support multiple, different attributes with their
 * implementation.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public interface AttributeValueMapper
{

    /**
     * Maps a single attribute value to a usable, common Java representation.
     *
     * @param attributeId
     *            the id of the attribute to map
     * @param attributeValue
     *            a single value of the attribute to map
     * @return the mapped value
     */
    public Object mapAttributeValue(String attributeId, Object attributeValue);
}
