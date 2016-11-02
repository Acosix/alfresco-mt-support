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

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.alfresco.repo.batch.BatchProcessor.BatchProcessWorkerAdaptor;
import org.alfresco.repo.tenant.TenantContextHolder;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.repo.tenant.TenantUtil;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.util.ParameterCheck;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public abstract class AbstractSyncBatchWorker<T> extends BatchProcessWorkerAdaptor<T>
{

    protected final String tenantDomain = TenantUtil.getCurrentDomain();

    protected final AuthorityService authorityService;

    protected final PersonService personService;

    protected final TenantService tenantService;

    public AbstractSyncBatchWorker(final ComponentLookupCallback componentLookup)
    {
        ParameterCheck.mandatory("componentLookup", componentLookup);

        this.authorityService = componentLookup.getComponent("authorityService", AuthorityService.class);
        this.personService = componentLookup.getComponent("personService", PersonService.class);
        this.tenantService = componentLookup.getComponent("tenantService", TenantService.class);
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void beforeProcess()
    {
        TenantContextHolder.setTenantDomain(this.tenantDomain);
    }

    protected void updateAuthorityZones(final String authorityName, final Collection<String> oldZones, final Collection<String> newZones)
    {
        final Set<String> zonesToRemove = new HashSet<>(oldZones);
        zonesToRemove.removeAll(newZones);
        // Let's keep the authority in the alfresco auth zone if it was already there. Otherwise we may have to
        // regenerate all paths to this authority from site groups, which could be very expensive!
        zonesToRemove.remove(AuthorityService.ZONE_AUTH_ALFRESCO);

        // divergence from default Alfresco: keep APP.XYZ zones alive because they have a different meaning
        for (final String oldZone : oldZones)
        {
            if (oldZone.startsWith("APP."))
            {
                zonesToRemove.remove(oldZone);
            }
        }

        if (!zonesToRemove.isEmpty())
        {
            this.authorityService.removeAuthorityFromZones(authorityName, zonesToRemove);
        }

        final Set<String> zonesToAdd = new HashSet<>(newZones);
        zonesToAdd.removeAll(oldZones);
        if (!zonesToAdd.isEmpty())
        {
            this.authorityService.addAuthorityToZones(authorityName, zonesToAdd);
        }
    }

    protected Set<String> newPersonSet()
    {
        return this.personService.getUserNamesAreCaseSensitive() ? new TreeSet<>() : new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    protected Map<String, Set<String>> newPersonMap()
    {
        return this.personService.getUserNamesAreCaseSensitive() ? new TreeMap<>() : new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }
}
