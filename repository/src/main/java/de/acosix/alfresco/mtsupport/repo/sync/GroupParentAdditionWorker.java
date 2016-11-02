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

import org.alfresco.util.ParameterCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class GroupParentAdditionWorker extends AbstractSyncBatchWorker<String>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(GroupParentAdditionWorker.class);

    protected final Map<String, Set<String>> groupParentsToAdd;

    public GroupParentAdditionWorker(final Map<String, Set<String>> groupParentsToAdd, final ComponentLookupCallback componentLookup)
    {
        super(componentLookup);

        ParameterCheck.mandatory("groupParentsToAdd", groupParentsToAdd);

        this.groupParentsToAdd = groupParentsToAdd;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void process(final String group) throws Throwable
    {
        final String groupShortName = this.authorityService.getShortName(group);
        final Set<String> parents = this.groupParentsToAdd.get(group);
        if (parents != null)
        {
            for (final String parent : parents)
            {
                LOGGER.debug("Adding {} to group {}", groupShortName, parent);
                this.authorityService.addAuthority(parent, group);
            }
        }
    }
}
