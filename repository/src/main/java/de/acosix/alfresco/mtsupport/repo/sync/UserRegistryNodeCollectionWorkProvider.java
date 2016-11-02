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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import org.alfresco.repo.batch.BatchProcessWorkProvider;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.repo.security.sync.ldap.LDAPUserRegistry;

/**
 * Instances of this class are used to optimise user / group synchronisation by better utilising the special collections provided by i.e.
 * {@link LDAPUserRegistry} which provide cursor-like retrieval of entities.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class UserRegistryNodeCollectionWorkProvider implements BatchProcessWorkProvider<NodeDescription>
{

    protected final Collection<NodeDescription> nodeCollection;

    protected final Iterator<NodeDescription> nodeIterator;

    public UserRegistryNodeCollectionWorkProvider(final Collection<NodeDescription> nodeCollection)
    {
        this.nodeCollection = nodeCollection;
        this.nodeIterator = nodeCollection.iterator();
    }

    @Override
    public int getTotalEstimatedWorkSize()
    {
        return this.nodeCollection.size();
    }

    @Override
    public Collection<NodeDescription> getNextWork()
    {
        final Collection<NodeDescription> nextWork = new ArrayList<>();
        while (this.nodeIterator.hasNext())
        {
            final NodeDescription next = this.nodeIterator.next();
            nextWork.add(next);

            //
            if (nextWork.size() >= (2 * TenantAwareChainingUserRegistrySynchronizer.USER_REGISTRY_ENTITY_BATCH_SIZE))
            {
                break;
            }
        }
        return nextWork;
    }

}
