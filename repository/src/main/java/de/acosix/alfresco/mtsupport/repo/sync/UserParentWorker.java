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

import java.util.Map;
import java.util.Set;

import org.alfresco.util.EqualsHelper;
import org.alfresco.util.ParameterCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class UserParentWorker extends AbstractSyncBatchWorker<String>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(UserParentWorker.class);

    protected final Map<String, Set<String>> userParentsToAdd;

    protected final Map<String, Set<String>> userParentsToRemove;

    public UserParentWorker(final Map<String, Set<String>> userParentsToAdd, final Map<String, Set<String>> userParentsToRemove,
            final ComponentLookupCallback componentLookup)
    {
        super(componentLookup);

        ParameterCheck.mandatory("userParentsToAdd", userParentsToAdd);
        ParameterCheck.mandatory("userParentsToRemove", userParentsToRemove);

        this.userParentsToAdd = userParentsToAdd;
        this.userParentsToRemove = userParentsToRemove;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void process(final String user) throws Throwable
    {
        final String domainUser;
        final String primaryDomain = this.tenantService.getPrimaryDomain(user);
        if (!EqualsHelper.nullSafeEquals(primaryDomain, this.tenantDomain))
        {
            domainUser = this.tenantService.getDomainUser(user, this.tenantDomain);
        }
        else
        {
            domainUser = user;
        }

        Set<String> parents = this.userParentsToAdd.get(user);
        if (parents != null)
        {
            for (final String parent : parents)
            {
                LOGGER.debug("Adding {} to group {}", domainUser, parent);
                this.authorityService.addAuthority(parent, domainUser);
            }
        }

        parents = this.userParentsToRemove.get(user);
        if (parents != null)
        {
            for (final String parent : parents)
            {
                LOGGER.debug("Removing {} fom group {}", domainUser, parent);
                this.authorityService.removeAuthority(parent, domainUser);
            }
        }
    }
}
