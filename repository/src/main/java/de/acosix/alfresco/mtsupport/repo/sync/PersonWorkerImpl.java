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

import java.io.Serializable;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.dictionary.constraint.NameChecker;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.EqualsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class PersonWorkerImpl extends AbstractZonedSyncBatchWorker<NodeDescription> implements PersonWorker
{

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonWorkerImpl.class);

    protected final NameChecker nameChecker;

    protected final AtomicLong latestModified = new AtomicLong(-1l);

    public PersonWorkerImpl(final String id, final String zoneId, final Set<String> targetZoneIds, final Collection<String> visitedIds,
            final Collection<String> allIds, final boolean allowDeletions, final ComponentLookupCallback componentLookup)
    {
        super(id, zoneId, targetZoneIds, visitedIds, allIds, allowDeletions, componentLookup);

        this.nameChecker = componentLookup.getComponent("nameChecker", NameChecker.class);
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public long getLatestModified()
    {
        return this.latestModified.get();
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public String getIdentifier(final NodeDescription entry)
    {
        return entry.getSourceId();
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void process(final NodeDescription person) throws Throwable
    {
        // Make a mutable copy of the person properties since they get written back to by person service
        final Map<QName, Serializable> personProperties = new HashMap<>(person.getProperties());
        final String personName = personProperties.get(ContentModel.PROP_USERNAME).toString().trim();
        final String domainUser;

        // divergence from Alfresco: adjust the user name for the tenant
        final String primaryDomain = this.tenantService.getPrimaryDomain(personName);
        if (!EqualsHelper.nullSafeEquals(primaryDomain, this.tenantDomain))
        {
            domainUser = this.tenantService.getDomainUser(personName, this.tenantDomain);
        }
        else
        {
            domainUser = personName;
        }
        personProperties.put(ContentModel.PROP_USERNAME, domainUser);

        this.nameChecker.evaluate(domainUser);

        final Set<String> personZones = this.authorityService.getAuthorityZones(domainUser);
        if (personZones == null)
        {
            LOGGER.debug("Creating user {}", domainUser);
            this.personService.createPerson(personProperties, this.targetZoneIds);
        }
        else if (personZones.contains(this.zoneId))
        {
            LOGGER.debug("Updating user {}", domainUser);
            this.personService.setPersonProperties(domainUser, personProperties, false);
        }
        else
        {
            // Check whether the user is in any of the authentication chain zones
            final Set<String> intersection = new TreeSet<>();
            for (final String groupZone : personZones)
            {
                if (groupZone.startsWith(AuthorityService.ZONE_AUTH_EXT_PREFIX))
                {
                    final String baseId = groupZone.substring(AuthorityService.ZONE_AUTH_EXT_PREFIX.length());
                    intersection.add(baseId);
                }
            }
            intersection.retainAll(this.allIds);
            // Check whether the user is in any of the higher priority authentication chain zones
            final Set<String> visited = new TreeSet<>(intersection);
            visited.retainAll(this.visitedIds);

            if (visited.size() == 0)
            {
                if (!this.allowDeletions || intersection.isEmpty())
                {
                    LOGGER.info("Updating user {} - this user will in future be assumed to originate from user registry {}", domainUser,
                            this.id);
                    this.updateAuthorityZones(personName, personZones, this.targetZoneIds);
                    this.personService.setPersonProperties(domainUser, personProperties, false);
                }
                else
                {
                    LOGGER.info(
                            "Recreating occluded user {}' - this user was previously created through synchronization with a lower priority user registry",
                            domainUser);
                    this.personService.deletePerson(domainUser);
                    this.personService.createPerson(personProperties, this.targetZoneIds);
                }
            }
        }

        final Date lastModified = person.getLastModified();
        this.latestModified.updateAndGet(oldLastModified -> {
            long newValue = lastModified.getTime();
            if (oldLastModified > newValue)
            {
                newValue = oldLastModified;
            }
            return newValue;
        });
    }
}
