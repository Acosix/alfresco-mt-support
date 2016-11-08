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

import java.io.Serializable;

import de.acosix.alfresco.mtsupport.repo.sync.UserAccountInterpreter;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class LDAPADUserAccountInterpreter implements UserAccountInterpreter
{

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean isUserAccountDisabled(final Serializable userAccountStatus)
    {
        final Boolean disabled;

        /*
         * References:
         * https://blogs.technet.microsoft.com/heyscriptingguy/2005/05/12/how-can-i-get-a-list-of-all-the-disabled-user-accounts-in-active-
         * directory
         * http://stackoverflow.com/questions/19250969/include-enabled-disabled-account-status-of-ldap-user-in-results/19252033#19252033
         */
        if (userAccountStatus != null)
        {
            final int status = Integer.parseInt(String.valueOf(userAccountStatus));
            disabled = (status & 2) != 0;
        }
        else
        {
            disabled = null;
        }

        return disabled;
    }

}
