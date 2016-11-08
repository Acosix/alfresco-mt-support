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
public class LDAPUserAccountInterpreter implements UserAccountInterpreter
{

    protected String disabledAccountPropertyValue = "";

    protected boolean acceptNullArgument;

    /**
     * @param disabledAccountPropertyValue
     *            the disabledAccountPropertyValue to set
     */
    public void setDisabledAccountPropertyValue(final String disabledAccountPropertyValue)
    {
        this.disabledAccountPropertyValue = disabledAccountPropertyValue;
    }

    /**
     * @param acceptNullArgument
     *            the acceptNullArgument to set
     */
    public void setAcceptNullArgument(final boolean acceptNullArgument)
    {
        this.acceptNullArgument = acceptNullArgument;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean isUserAccountDisabled(final Serializable userAccountStatus)
    {
        final Boolean disabled;

        if (userAccountStatus != null)
        {
            disabled = String.valueOf(userAccountStatus).equals(this.disabledAccountPropertyValue);
        }
        else if (this.acceptNullArgument)
        {
            disabled = false;
        }
        else
        {
            disabled = null;
        }

        return disabled;
    }

}
