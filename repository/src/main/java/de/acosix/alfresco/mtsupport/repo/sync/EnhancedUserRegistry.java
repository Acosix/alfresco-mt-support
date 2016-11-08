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

import org.alfresco.repo.security.sync.UserRegistry;

/**
 * Instances of this interface provide additional functionality over plain user registries. Such functionalities may be introduced in
 * Alfresco default classes of various versions as implementation details, i.e. like the introduction of the
 * {@code LDAPUserRegistry#getUserAccountStatusInterpreter()} in 5.2. This interface aims to make those first-level citizens of the API.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public interface EnhancedUserRegistry extends UserRegistry
{

    /**
     * Retrieves the component responsible for interpreting specific details about an user account.
     *
     * @return the user account interpreter
     */
    UserAccountInterpreter getUserAccountInterpreter();

}
