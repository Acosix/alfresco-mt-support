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
import java.util.Set;

import org.alfresco.util.ParameterCheck;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public abstract class AbstractZonedSyncBatchWorker<T> extends AbstractSyncBatchWorker<T>
{

    protected final String id;

    protected final String zoneId;

    protected final Set<String> targetZoneIds;

    protected final Collection<String> visitedIds;

    protected final Collection<String> allIds;

    protected final boolean allowDeletions;

    public AbstractZonedSyncBatchWorker(final String id, final String zoneId, final Set<String> targetZoneIds, final Collection<String> visitedIds,
            final Collection<String> allIds, final boolean allowDeletions, final ComponentLookupCallback componentLookup)
    {
        super(componentLookup);

        ParameterCheck.mandatoryString("id", id);
        ParameterCheck.mandatoryString("zoneId", zoneId);
        ParameterCheck.mandatoryCollection("targetZoneIds", targetZoneIds);
        ParameterCheck.mandatory("visitedIds", visitedIds);
        ParameterCheck.mandatoryCollection("allIds", allIds);

        this.id = id;
        this.zoneId = zoneId;
        this.targetZoneIds = targetZoneIds;
        this.visitedIds = visitedIds;
        this.allIds = allIds;
        this.allowDeletions = allowDeletions;
    }
}
