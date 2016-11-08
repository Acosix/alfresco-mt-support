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
package de.acosix.alfresco.mtsupport.repo.sync;

import java.io.Serializable;

import org.alfresco.repo.security.sync.UserRegistry;

/**
 * Instances of this interface provide callback operations for {@link UserRegistry} synchronising client code to interpret values from user
 * properties.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public interface UserAccountInterpreter
{

    /**
     * Checks a specific property value (typically mapped as cm:userAccountStatusProperty) against the expected value denoting a disabled
     * user account.
     *
     * @param userAccountStatus
     *            the property value to check
     * @return {@code true} if the user account should be considered disabled based on the provided value, {@code null} if no decisive
     *         judgement can be passed
     */
    Boolean isUserAccountDisabled(Serializable userAccountStatus);

}
