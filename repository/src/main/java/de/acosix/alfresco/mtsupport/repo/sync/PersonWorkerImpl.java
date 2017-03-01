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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.dictionary.constraint.NameChecker;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.service.cmr.repository.AssociationRef;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.namespace.RegexQNamePattern;
import org.alfresco.util.EqualsHelper;
import org.apache.commons.codec.binary.Hex;
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

    protected UserAccountInterpreter accountInterpreter;

    protected NodeService nodeService;

    protected ContentService contentService;

    public PersonWorkerImpl(final String id, final String zoneId, final Set<String> targetZoneIds, final Collection<String> visitedIds,
            final Collection<String> allIds, final boolean allowDeletions, final UserAccountInterpreter accountInterpreter,
            final ComponentLookupCallback componentLookup)
    {
        super(id, zoneId, targetZoneIds, visitedIds, allIds, allowDeletions, componentLookup);

        this.accountInterpreter = accountInterpreter;
        this.nameChecker = componentLookup.getComponent("nameChecker", NameChecker.class);
        this.nodeService = componentLookup.getComponent("nodeService", NodeService.class);
        this.contentService = componentLookup.getComponent("contentService", ContentService.class);
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

        if (this.accountInterpreter != null)
        {
            final QName propertyNameToCheck = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "userAccountStatusProperty");
            final Serializable propertyValue = personProperties.get(propertyNameToCheck);
            final Boolean disabled = this.accountInterpreter.isUserAccountDisabled(propertyValue);
            if (disabled != null)
            {
                personProperties.put(ContentModel.PROP_ENABLED, disabled);
            }
        }

        this.nameChecker.evaluate(domainUser);

        NodeRef personRef = null;
        Serializable avatarValue = null;

        final Set<String> personZones = this.authorityService.getAuthorityZones(domainUser);
        if (personZones == null)
        {
            LOGGER.debug("Creating user {}", domainUser);

            avatarValue = personProperties.remove(ContentModel.ASSOC_AVATAR);
            personRef = this.personService.createPerson(personProperties, this.targetZoneIds);
        }
        else if (personZones.contains(this.zoneId))
        {
            LOGGER.debug("Updating user {}", domainUser);

            avatarValue = personProperties.remove(ContentModel.ASSOC_AVATAR);
            personRef = this.personService.getPerson(domainUser);
            this.personService.setPersonProperties(domainUser, personProperties, false);
        }
        else
        {
            personRef = this.personService.getPerson(domainUser);

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
                            "Recreating occluded user {} - this user was previously created through synchronization with a lower priority user registry",
                            domainUser);
                    this.personService.deletePerson(domainUser);

                    avatarValue = personProperties.remove(ContentModel.ASSOC_AVATAR);
                    personRef = this.personService.createPerson(personProperties, this.targetZoneIds);
                }
            }
        }

        if (personRef != null && avatarValue != null)
        {
            this.handleAvatar(domainUser, personRef, avatarValue);
        }

        final Date lastModified = person.getLastModified();
        if (lastModified != null)
        {
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

    protected void handleAvatar(final String userName, final NodeRef person, final Serializable avatarValue)
    {
        if (avatarValue instanceof AvatarBlobWrapper)
        {
            LOGGER.debug("Checking for existing preference image for {}", userName);

            final QName expectedQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "mt-ldap-synch");
            final List<ChildAssociationRef> childAssocs = this.nodeService.getChildAssocs(person, ContentModel.ASSOC_PREFERENCE_IMAGE,
                    RegexQNamePattern.MATCH_ALL);
            if (childAssocs.isEmpty())
            {
                final NodeRef childRef = this.nodeService
                        .createNode(person, ContentModel.ASSOC_PREFERENCE_IMAGE, expectedQName, ContentModel.TYPE_CONTENT).getChildRef();
                final ContentWriter writer = this.contentService.getWriter(childRef, ContentModel.PROP_CONTENT, true);
                writer.guessEncoding();
                writer.guessMimetype(null);
                try (OutputStream contentOutputStream = writer.getContentOutputStream())
                {
                    contentOutputStream.write(((AvatarBlobWrapper) avatarValue).getData());
                }
                catch (final IOException ioex)
                {
                    LOGGER.warn("Error writing new person avatar", ioex);
                }

                final List<AssociationRef> existingAvatarAssocs = this.nodeService.getTargetAssocs(person, ContentModel.ASSOC_AVATAR);
                existingAvatarAssocs.forEach((x) -> {
                    this.nodeService.removeAssociation(person, x.getTargetRef(), x.getTypeQName());
                });
                this.nodeService.createAssociation(person, childRef, ContentModel.ASSOC_AVATAR);

                LOGGER.debug("Created new preference image for {}: {}", userName, childRef);
            }
            else
            {
                final ChildAssociationRef childAssociation = childAssocs.get(0);
                final NodeRef childRef = childAssociation.getChildRef();

                LOGGER.debug("Checking for differences with existing preference image of person {}", person);
                if (this.checkForDigestDifferences((AvatarBlobWrapper) avatarValue, childRef))
                {
                    if (!EqualsHelper.nullSafeEquals(childAssociation.getQName(), expectedQName))
                    {
                        this.nodeService.moveNode(childRef, person, ContentModel.ASSOC_PREFERENCE_IMAGE, expectedQName);
                    }

                    final ContentWriter writer = this.contentService.getWriter(childRef, ContentModel.PROP_CONTENT, true);
                    writer.guessEncoding();
                    writer.guessMimetype(null);
                    try (OutputStream contentOutputStream = writer.getContentOutputStream())
                    {
                        contentOutputStream.write(((AvatarBlobWrapper) avatarValue).getData());

                        LOGGER.debug("Updated preference image for {}: {}", userName, childRef);
                    }
                    catch (final IOException ioex)
                    {
                        LOGGER.warn("Error writing new person avatar", ioex);
                    }
                }
            }
        }
    }

    protected boolean checkForDigestDifferences(final AvatarBlobWrapper avatarWrapper, final NodeRef preferenceImage)
    {
        String newImageDigestHexStr = null;
        String existingImageDigestHexStr = null;

        try
        {
            final MessageDigest newImageMD = MessageDigest.getInstance("MD5");
            newImageMD.update(avatarWrapper.getData());
            final byte[] newImageDigest = newImageMD.digest();
            final char[] newImageDigestHex = Hex.encodeHex(newImageDigest, false);
            newImageDigestHexStr = new String(newImageDigestHex);

            final MessageDigest existingImageMD = MessageDigest.getInstance("MD5");
            final ContentReader existingImageReader = this.contentService.getReader(preferenceImage, ContentModel.PROP_CONTENT);
            try (InputStream contentInputStream = existingImageReader.getContentInputStream())
            {
                final byte[] buffer = new byte[1024 * 512];
                int bytesRead = -1;
                while ((bytesRead = contentInputStream.read(buffer)) != -1)
                {
                    existingImageMD.update(buffer, 0, bytesRead);
                }

                final byte[] existimageDigest = existingImageMD.digest();
                final char[] existingImageDigestHex = Hex.encodeHex(existimageDigest, false);
                existingImageDigestHexStr = new String(existingImageDigestHex);
            }
            catch (final IOException ioex)
            {
                LOGGER.warn("Error creating digest from existing person avatar", ioex);
            }
        }
        catch (final NoSuchAlgorithmException dex)
        {
            LOGGER.warn("Error creating digest for new/existing person avatar", dex);
        }

        final boolean difference = !EqualsHelper.nullSafeEquals(newImageDigestHexStr, existingImageDigestHexStr, true);
        return difference;
    }
}
