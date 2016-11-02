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

import java.util.Collections;
import java.util.Set;

import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.util.EqualsHelper;
import org.alfresco.util.ParameterCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class AuthorityDeleter extends AbstractSyncBatchWorker<String>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorityDeleter.class);

    protected String zoneId;

    protected final Set<String> groupsToDelete;

    protected final Set<String> usersToDelete;

    protected final boolean allowDeletions;

    public AuthorityDeleter(final String zoneId, final Set<String> groupsToDelete, final Set<String> usersToDelete,
            final boolean allowDeletions, final ComponentLookupCallback componentLookup)
    {
        super(componentLookup);

        ParameterCheck.mandatoryString("zoneId", zoneId);
        ParameterCheck.mandatory("groupsToDelete", groupsToDelete);
        ParameterCheck.mandatory("usersToDelete", usersToDelete);

        this.zoneId = zoneId;
        this.groupsToDelete = groupsToDelete;
        this.usersToDelete = usersToDelete;
        this.allowDeletions = allowDeletions;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void process(final String authority) throws Throwable
    {
        if (AuthorityType.getAuthorityType(authority) == AuthorityType.USER)
        {
            final String domainUser;
            final String primaryDomain = this.tenantService.getPrimaryDomain(authority);
            if (!EqualsHelper.nullSafeEquals(primaryDomain, this.tenantDomain))
            {
                domainUser = this.tenantService.getDomainUser(authority, this.tenantDomain);
            }
            else
            {
                domainUser = authority;
            }

            if (this.allowDeletions)
            {
                LOGGER.debug("Deleting user {}", domainUser);
                this.personService.deletePerson(domainUser);
            }
            else
            {
                this.updateAuthorityZones(domainUser, Collections.singleton(this.zoneId),
                        Collections.singleton(AuthorityService.ZONE_AUTH_ALFRESCO));
            }
        }
        else
        {
            if (this.allowDeletions)
            {
                LOGGER.debug("Deleting group {}", this.authorityService.getShortName(authority));
                this.authorityService.deleteAuthority(authority);
            }
            else
            {
                this.updateAuthorityZones(authority, Collections.singleton(this.zoneId),
                        Collections.singleton(AuthorityService.ZONE_AUTH_ALFRESCO));
            }
        }
    }
}
