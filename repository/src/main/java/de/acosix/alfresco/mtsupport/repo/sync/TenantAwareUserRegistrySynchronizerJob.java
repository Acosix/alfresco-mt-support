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

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.sync.UserRegistrySynchronizer;
import org.alfresco.repo.tenant.TenantAdminService;
import org.alfresco.repo.tenant.TenantUtil;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantAwareUserRegistrySynchronizerJob implements Job
{

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantAwareUserRegistrySynchronizerJob.class);

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void execute(final JobExecutionContext executionContext) throws JobExecutionException
    {
        final UserRegistrySynchronizer userRegistrySynchronizer = (UserRegistrySynchronizer) executionContext.getJobDetail().getJobDataMap()
                .get("userRegistrySynchronizer");
        final TenantAdminService tenantAdminService = (TenantAdminService) executionContext.getJobDetail().getJobDataMap()
                .get("tenantAdminService");
        final String synchronizeChangesOnly = (String) executionContext.getJobDetail().getJobDataMap().get("synchronizeChangesOnly");
        final boolean forceUpdate = synchronizeChangesOnly == null || !Boolean.parseBoolean(synchronizeChangesOnly);
        final String tenantDomain = (String) executionContext.getJobDetail().getJobDataMap().get("tenantDomain");

        if (TenantUtil.DEFAULT_TENANT.equals(tenantDomain))
        {
            LOGGER.debug("Triggering synchronization for default tenant");
            AuthenticationUtil.runAs(() -> {
                userRegistrySynchronizer.synchronize(forceUpdate, true);
                return null;
            }, AuthenticationUtil.getSystemUserName());
        }
        else if (tenantAdminService.isEnabled() && tenantAdminService.existsTenant(tenantDomain)
                && tenantAdminService.isEnabledTenant(tenantDomain))
        {
            LOGGER.debug("Triggering synchronization for {} tenant", tenantDomain);
            TenantUtil.runAsSystemTenant(() -> {
                userRegistrySynchronizer.synchronize(forceUpdate, true);
                return null;
            }, tenantDomain);
        }
        else
        {
            LOGGER.debug("Failed to trigger snychronization as either multi-tenancy is disabled, tenant {} does not exist or is disabled",
                    tenantDomain);
        }
    }
}
