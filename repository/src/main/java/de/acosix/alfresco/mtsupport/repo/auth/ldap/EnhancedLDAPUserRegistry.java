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

import org.alfresco.repo.security.sync.ldap.LDAPUserRegistry;

import de.acosix.alfresco.mtsupport.repo.sync.EnhancedUserRegistry;
import de.acosix.alfresco.mtsupport.repo.sync.UserAccountInterpreter;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class EnhancedLDAPUserRegistry extends LDAPUserRegistry implements EnhancedUserRegistry
{

    protected UserAccountInterpreter userAccountInterpreter;

    /**
     * @param userAccountInterpreter
     *            the userAccountInterpreter to set
     */
    public void setUserAccountInterpreter(final UserAccountInterpreter userAccountInterpreter)
    {
        this.userAccountInterpreter = userAccountInterpreter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UserAccountInterpreter getUserAccountInterpreter()
    {
        return this.userAccountInterpreter;
    }

}
