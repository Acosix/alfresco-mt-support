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
import org.alfresco.repo.tenant.TenantService;

/**
 * This special interface marks any user registry that is capable of handling more than just the {@link TenantService#DEFAULT_DOMAIN default
 * tenant}.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public interface MTAwareUserRegistry extends UserRegistry
{

    /**
     * Checks if the user registry has been enabled to handle a specific tenant.
     *
     * @param tenantDomain
     *            the tenant to check
     * @return {@code true} if user registry has been enabled to handle tenant, {@code false} otherwise
     */
    boolean isActiveForTenant(String tenantDomain);

}
