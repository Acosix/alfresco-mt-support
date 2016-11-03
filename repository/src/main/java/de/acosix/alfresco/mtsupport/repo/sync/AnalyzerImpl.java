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
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.util.PropertyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareChainingUserRegistrySynchronizer.ComponentLookupCallback;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class AnalyzerImpl extends AbstractZonedSyncBatchWorker<NodeDescription> implements Analyzer
{

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzerImpl.class);

    protected final AtomicLong latestModified = new AtomicLong(-1l);

    protected final Map<String, Set<String>> membersCache = new TreeMap<>();

    protected final Map<String, String> groupsToCreate = new TreeMap<>();

    protected final Map<String, Set<String>> userParentsToAdd;

    protected final Map<String, Set<String>> userParentsToRemove;

    protected final Map<String, Set<String>> groupParentsToAdd = new TreeMap<>();

    protected final Map<String, Set<String>> groupParentsToRemove = new TreeMap<>();

    public AnalyzerImpl(final String id, final String zoneId, final Set<String> targetZoneIds, final Collection<String> visitedIds,
            final Collection<String> allIds, final boolean allowDeletions, final ComponentLookupCallback componentLookup)
    {
        super(id, zoneId, targetZoneIds, visitedIds, allIds, allowDeletions, componentLookup);

        this.userParentsToAdd = this.newPersonMap();
        this.userParentsToRemove = this.newPersonMap();
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
    public Map<String, String> getGroupsToCreate()
    {
        return this.groupsToCreate;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Map<String, Set<String>> getUserParentsToAdd()
    {
        return this.userParentsToAdd;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Map<String, Set<String>> getUserParentsToRemove()
    {
        return this.userParentsToRemove;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Map<String, Set<String>> getGroupParentsToAdd()
    {
        return this.groupParentsToAdd;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Map<String, Set<String>> getGroupParentsToRemove()
    {
        return this.groupParentsToRemove;
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
    public void process(final NodeDescription group) throws Throwable
    {
        final PropertyMap groupProperties = group.getProperties();
        final String groupName = (String) groupProperties.get(ContentModel.PROP_AUTHORITY_NAME);
        final String groupShortName = this.authorityService.getShortName(groupName);
        final Set<String> groupZones = this.authorityService.getAuthorityZones(groupName);

        // TODO Alfresco included update/creation in Analyzer, but we should aim to externalize this
        if (groupZones == null)
        {
            this.updateGroup(group, false);
        }
        else
        {
            // Check whether the group is in any of the authentication chain zones
            final Set<String> intersection = new TreeSet<>();
            for (final String groupZone : groupZones)
            {
                if (groupZone.startsWith(AuthorityService.ZONE_AUTH_EXT_PREFIX))
                {
                    final String baseId = groupZone.substring(AuthorityService.ZONE_AUTH_EXT_PREFIX.length());
                    intersection.add(baseId);
                }
            }
            intersection.retainAll(this.allIds);
            // Check whether the group is in any of the higher priority authentication chain zones
            final Set<String> visited = new TreeSet<>(intersection);
            visited.retainAll(this.visitedIds);

            if (groupZones.contains(this.zoneId))
            {
                // The group already existed in this zone: update the group
                this.updateGroup(group, true);
            }
            else if (visited.isEmpty())
            {
                if (!this.allowDeletions || intersection.isEmpty())
                {
                    // Deletions are disallowed or the group exists, but not in a zone that's in the authentication
                    // chain. May be due to upgrade or zone changes. Let's re-zone them
                    LOGGER.warn("Updating group {} - this group will in future be assumed to originate from user registry {}",
                            groupShortName, this.id);
                    this.updateAuthorityZones(groupName, groupZones, this.targetZoneIds);

                    // The group now exists in this zone: update the group
                    this.updateGroup(group, true);
                }
                else
                {
                    // The group existed, but in a zone with lower precedence
                    LOGGER.warn(
                            "Recreating occluded group {} - this group was previously created through synchronization with a lower priority user registry",
                            groupShortName);
                    this.authorityService.deleteAuthority(groupName);

                    // create the group
                    this.updateGroup(group, false);
                }
            }
        }

        final Date lastModified = group.getLastModified();
        this.latestModified.updateAndGet(oldLastModified -> {
            long newValue = lastModified.getTime();
            if (oldLastModified > newValue)
            {
                newValue = oldLastModified;
            }
            return newValue;
        });
    }

    protected void updateGroup(final NodeDescription group, final boolean existed)
    {
        final PropertyMap groupProperties = group.getProperties();
        final String groupName = (String) groupProperties.get(ContentModel.PROP_AUTHORITY_NAME);
        String groupDisplayName = (String) groupProperties.get(ContentModel.PROP_AUTHORITY_DISPLAY_NAME);
        if (groupDisplayName == null)
        {
            groupDisplayName = this.authorityService.getShortName(groupName);
        }

        final Set<String> userMembers = this.newPersonSet();
        final Set<String> groupMembers = new TreeSet<>();

        for (final String child : group.getChildAssociations())
        {
            if (AuthorityType.getAuthorityType(child) == AuthorityType.USER)
            {
                userMembers.add(child);
            }
            else
            {
                groupMembers.add(child);
            }
        }

        if (existed)
        {
            this.authorityService.setAuthorityDisplayName(groupName, groupDisplayName);

            final Set<String> containedAuthorities = this.getContainedAuthorities(groupName);
            for (final String child : containedAuthorities)
            {
                if (AuthorityType.getAuthorityType(child) == AuthorityType.USER)
                {
                    if (!userMembers.remove(child))
                    {
                        this.recordParentForRemoval(child, groupName);
                    }
                }
                else
                {
                    if (!groupMembers.remove(child))
                    {
                        this.recordParentForRemoval(child, groupName);
                    }
                }
            }
        }
        else
        {
            synchronized (this.groupsToCreate)
            {
                this.groupsToCreate.put(groupName, groupDisplayName);
            }
        }

        for (final String child : userMembers)
        {
            this.recordParentForAddition(child, groupName);
        }

        for (final String child : groupMembers)
        {
            this.recordParentForAddition(child, groupName);
        }
    }

    protected Set<String> getContainedAuthorities(final String groupName)
    {
        // Return the cached children if it is processed
        Set<String> children = this.membersCache.get(groupName);
        if (children == null)
        {
            // need to ensure all direct/transitive parent groups are cached
            final Set<String> containingAuthorities = this.authorityService.getContainingAuthorities(AuthorityType.GROUP, groupName, true);
            for (final String parent : containingAuthorities)
            {
                this.getContainedAuthorities(parent);
            }

            children = this.cacheContainedAuthorities(groupName);
        }

        return children;
    }

    protected Set<String> cacheContainedAuthorities(final String groupName)
    {
        Set<String> children;

        if (!this.membersCache.containsKey(groupName))
        {
            synchronized (this.membersCache)
            {
                children = this.membersCache.get(groupName);
                if (children == null)
                {
                    children = this.authorityService.getContainedAuthorities(null, groupName, true);
                    this.membersCache.put(groupName, Collections.synchronizedSet(children));
                }
            }
        }
        else
        {
            children = this.membersCache.get(groupName);
        }
        return children;
    }

    protected void recordParentForRemoval(final String child, final String parent)
    {
        Map<String, Set<String>> parentsMutationCache;
        if (AuthorityType.getAuthorityType(child) == AuthorityType.USER)
        {
            parentsMutationCache = this.userParentsToRemove;
        }
        else
        {
            // Reflect the change in the map of final group associations (for cycle detection later)
            parentsMutationCache = this.groupParentsToRemove;

            final Set<String> children = this.getContainedAuthorities(parent);
            children.remove(child);
        }

        final Set<String> parents = this.getParentsFromMutationCache(child, parentsMutationCache);
        parents.add(parent);
    }

    protected void recordParentForAddition(final String child, final String parent)
    {
        final Map<String, Set<String>> parentsMutationCache = AuthorityType.getAuthorityType(child) == AuthorityType.USER
                ? this.userParentsToAdd : this.groupParentsToAdd;

        final Set<String> parents = this.getParentsFromMutationCache(child, parentsMutationCache);

        parents.add(parent);
    }

    protected Set<String> getParentsFromMutationCache(final String child, final Map<String, Set<String>> parentsMutationCache)
    {
        Set<String> parents;
        if (!parentsMutationCache.containsKey(child))
        {
            synchronized (parentsMutationCache)
            {
                parents = parentsMutationCache.get(child);
                if (parents == null)
                {
                    parents = new TreeSet<>();
                    parentsMutationCache.put(child, Collections.synchronizedSet(parents));
                }
            }
        }
        else
        {
            parents = parentsMutationCache.get(child);
        }
        return parents;
    }
}
