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
package de.acosix.alfresco.mtsupport.repo.auth.ldap;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapName;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.management.subsystems.ActivateableBean;
import org.alfresco.repo.security.authentication.AuthenticationDiagnostic;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.ldap.LDAPInitialDirContextFactory;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.repo.security.sync.ldap.LDAPNameResolver;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.alfresco.util.PropertyCheck;
import org.alfresco.util.PropertyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.acosix.alfresco.mtsupport.repo.sync.EnhancedUserRegistry;
import de.acosix.alfresco.mtsupport.repo.sync.UserAccountInterpreter;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class EnhancedLDAPUserRegistry implements EnhancedUserRegistry, LDAPNameResolver, InitializingBean, ActivateableBean
{

    private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedLDAPUserRegistry.class);

    /** The regular expression that will match the attribute at the end of a range. */
    private static final Pattern PATTERN_RANGE_END = Pattern.compile(";range=[0-9]+-\\*");

    /** Is this bean active? I.e. should this part of the subsystem be used? */
    private boolean active = true;

    /** Enable progress estimation? When enabled, the user query has to be run twice in order to count entries. */
    protected boolean enableProgressEstimation = true;

    /** The group query. */
    protected String groupQuery = "(objectclass=groupOfNames)";

    /** The group differential query. */
    protected String groupDifferentialQuery = "(&(objectclass=groupOfNames)(!(modifyTimestamp<={0})))";

    /** The person query. */
    protected String personQuery = "(objectclass=inetOrgPerson)";

    /** The person differential query. */
    protected String personDifferentialQuery = "(&(objectclass=inetOrgPerson)(!(modifyTimestamp<={0})))";

    /** The group search base. */
    protected String groupSearchBase;

    /** The user search base. */
    protected String userSearchBase;

    /** The group id attribute name. */
    protected String groupIdAttributeName = "cn";

    /** The user id attribute name. */
    protected String userIdAttributeName = "uid";

    /** The member attribute name. */
    protected String memberAttributeName = "member";

    /** The modification timestamp attribute name. */
    protected String modifyTimestampAttributeName = "modifyTimestamp";

    /** The group type. */
    protected String groupType = "groupOfNames";

    /** The person type. */
    protected String personType = "inetOrgPerson";

    /** The ldap initial context factory. */
    protected LDAPInitialDirContextFactory ldapInitialContextFactory;

    /** The namespace service. */
    protected NamespaceService namespaceService;

    /** The person attribute mapping. */
    protected Map<String, String> personAttributeMapping;

    /** The person attribute defaults. */
    protected Map<String, String> personAttributeDefaults = Collections.emptyMap();

    /** The group attribute mapping. */
    protected Map<String, String> groupAttributeMapping;

    /** The group attribute defaults. */
    protected Map<String, String> groupAttributeDefaults = Collections.emptyMap();

    /**
     * The query batch size. If positive, indicates that RFC 2696 paged results should be used to split query results
     * into batches of the specified size. Overcomes any size limits imposed by the LDAP server.
     */
    protected int queryBatchSize;

    /**
     * The attribute retrieval batch size. If positive, indicates that range retrieval should be used to fetch
     * multi-valued attributes (such as member) in batches of the specified size. Overcomes any size limits imposed by
     * the LDAP server.
     */
    protected int attributeBatchSize;

    /** Should we error on missing group members?. */
    protected boolean errorOnMissingMembers;

    /** Should we error on duplicate group IDs?. */
    protected boolean errorOnDuplicateGID;

    /** Should we error on missing group IDs?. */
    protected boolean errorOnMissingGID = false;

    /** Should we error on missing user IDs?. */
    protected boolean errorOnMissingUID = false;

    /** An array of all LDAP attributes to be queried from users plus a set of property QNames. */
    protected Pair<String[], Set<QName>> userKeys;

    /** An array of all LDAP attributes to be queried from groups plus a set of property QNames. */
    protected Pair<String[], Set<QName>> groupKeys;

    /** The LDAP generalized time format. */
    protected DateFormat timestampFormat;

    protected UserAccountInterpreter userAccountInterpreter;

    protected Map<String, AttributeValueMapper> attributeValueMappers;

    public EnhancedLDAPUserRegistry()
    {
        // Default to official LDAP generalized time format (unfortunately not used by Active Directory)
        this.setTimestampFormat("yyyyMMddHHmmss'Z'");
    }

    /**
     * Controls whether this bean is active. I.e. should this part of the subsystem be used?
     *
     * @param active
     *            <code>true</code> if this bean is active
     */
    public void setActive(final boolean active)
    {
        this.active = active;
    }

    /**
     * Controls whether progress estimation is enabled. When enabled, the user query has to be run twice in order to
     * count entries.
     *
     * @param enableProgressEstimation
     *            <code>true</code> if progress estimation is enabled
     */
    public void setEnableProgressEstimation(final boolean enableProgressEstimation)
    {
        this.enableProgressEstimation = enableProgressEstimation;
    }

    /**
     * Sets the group id attribute name.
     *
     * @param groupIdAttributeName
     *            the group id attribute name
     */
    public void setGroupIdAttributeName(final String groupIdAttributeName)
    {
        this.groupIdAttributeName = groupIdAttributeName;
    }

    /**
     * Sets the group query.
     *
     * @param groupQuery
     *            the group query
     */
    public void setGroupQuery(final String groupQuery)
    {
        this.groupQuery = groupQuery;
    }

    /**
     * Sets the group differential query.
     *
     * @param groupDifferentialQuery
     *            the group differential query
     */
    public void setGroupDifferentialQuery(final String groupDifferentialQuery)
    {
        this.groupDifferentialQuery = groupDifferentialQuery;
    }

    /**
     * Sets the person query.
     *
     * @param personQuery
     *            the person query
     */
    public void setPersonQuery(final String personQuery)
    {
        this.personQuery = personQuery;
    }

    /**
     * Sets the person differential query.
     *
     * @param personDifferentialQuery
     *            the person differential query
     */
    public void setPersonDifferentialQuery(final String personDifferentialQuery)
    {
        this.personDifferentialQuery = personDifferentialQuery;
    }

    /**
     * Sets the group type.
     *
     * @param groupType
     *            the group type
     */
    public void setGroupType(final String groupType)
    {
        this.groupType = groupType;
    }

    /**
     * Sets the member attribute name.
     *
     * @param memberAttribute
     *            the member attribute name
     */
    public void setMemberAttribute(final String memberAttribute)
    {
        this.memberAttributeName = memberAttribute;
    }

    /**
     * Sets the person type.
     *
     * @param personType
     *            the person type
     */
    public void setPersonType(final String personType)
    {
        this.personType = personType;
    }

    /**
     * Sets the group search base.
     *
     * @param groupSearchBase
     *            the group search base
     */
    public void setGroupSearchBase(final String groupSearchBase)
    {
        this.groupSearchBase = groupSearchBase;
    }

    /**
     * Sets the user search base.
     *
     * @param userSearchBase
     *            the user search base
     */
    public void setUserSearchBase(final String userSearchBase)
    {
        this.userSearchBase = userSearchBase;
    }

    /**
     * Sets the user id attribute name.
     *
     * @param userIdAttributeName
     *            the user id attribute name
     */
    public void setUserIdAttributeName(final String userIdAttributeName)
    {
        this.userIdAttributeName = userIdAttributeName;
    }

    /**
     * Sets the modification timestamp attribute name.
     *
     * @param modifyTimestampAttributeName
     *            the modification timestamp attribute name
     */
    public void setModifyTimestampAttributeName(final String modifyTimestampAttributeName)
    {
        this.modifyTimestampAttributeName = modifyTimestampAttributeName;
    }

    /**
     * Sets the timestamp format. Unfortunately, this varies between directory servers.
     *
     * @param timestampFormat
     *            the timestamp format
     *            <ul>
     *            <li>OpenLDAP: "yyyyMMddHHmmss'Z'"
     *            <li>Active Directory: "yyyyMMddHHmmss'.0Z'"
     *            </ul>
     */
    public void setTimestampFormat(final String timestampFormat)
    {
        this.timestampFormat = new SimpleDateFormat(timestampFormat, Locale.UK);
        this.timestampFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    /**
     * Decides whether to error on missing group members.
     *
     * @param errorOnMissingMembers
     *            <code>true</code> if we should error on missing group members
     */
    public void setErrorOnMissingMembers(final boolean errorOnMissingMembers)
    {
        this.errorOnMissingMembers = errorOnMissingMembers;
    }

    /**
     * Decides whether to error on missing group IDs.
     *
     * @param errorOnMissingGID
     *            <code>true</code> if we should error on missing group IDs
     */
    public void setErrorOnMissingGID(final boolean errorOnMissingGID)
    {
        this.errorOnMissingGID = errorOnMissingGID;
    }

    /**
     * Decides whether to error on missing user IDs.
     *
     * @param errorOnMissingUID
     *            <code>true</code> if we should error on missing user IDs
     */
    public void setErrorOnMissingUID(final boolean errorOnMissingUID)
    {
        this.errorOnMissingUID = errorOnMissingUID;
    }

    /**
     * Decides whether to error on duplicate group IDs.
     *
     * @param errorOnDuplicateGID
     *            <code>true</code> if we should error on duplicate group IDs
     */
    public void setErrorOnDuplicateGID(final boolean errorOnDuplicateGID)
    {
        this.errorOnDuplicateGID = errorOnDuplicateGID;
    }

    /**
     * Sets the LDAP initial dir context factory.
     *
     * @param ldapInitialDirContextFactory
     *            the new LDAP initial dir context factory
     */
    public void setLDAPInitialDirContextFactory(final LDAPInitialDirContextFactory ldapInitialDirContextFactory)
    {
        this.ldapInitialContextFactory = ldapInitialDirContextFactory;
    }

    /**
     * Sets the namespace service.
     *
     * @param namespaceService
     *            the namespace service
     */
    public void setNamespaceService(final NamespaceService namespaceService)
    {
        this.namespaceService = namespaceService;
    }

    /**
     * Sets the person attribute defaults.
     *
     * @param personAttributeDefaults
     *            the person attribute defaults
     */
    public void setPersonAttributeDefaults(final Map<String, String> personAttributeDefaults)
    {
        this.personAttributeDefaults = personAttributeDefaults;
    }

    /**
     * Sets the person attribute mapping.
     *
     * @param personAttributeMapping
     *            the person attribute mapping
     */
    public void setPersonAttributeMapping(final Map<String, String> personAttributeMapping)
    {
        this.personAttributeMapping = personAttributeMapping;
    }

    /**
     * Sets the group attribute defaults.
     *
     * @param groupAttributeDefaults
     *            the group attribute defaults
     */
    public void setGroupAttributeDefaults(final Map<String, String> groupAttributeDefaults)
    {
        this.groupAttributeDefaults = groupAttributeDefaults;
    }

    /**
     * Sets the group attribute mapping.
     *
     * @param groupAttributeMapping
     *            the group attribute mapping
     */
    public void setGroupAttributeMapping(final Map<String, String> groupAttributeMapping)
    {
        this.groupAttributeMapping = groupAttributeMapping;
    }

    /**
     * Sets the query batch size.
     *
     * @param queryBatchSize
     *            If positive, indicates that RFC 2696 paged results should be used to split query results into batches
     *            of the specified size. Overcomes any size limits imposed by the LDAP server.
     */
    public void setQueryBatchSize(final int queryBatchSize)
    {
        this.queryBatchSize = queryBatchSize;
    }

    /**
     * Sets the attribute batch size.
     *
     * @param attributeBatchSize
     *            If positive, indicates that range retrieval should be used to fetch multi-valued attributes (such as
     *            member) in batches of the specified size. Overcomes any size limits imposed by the LDAP server.
     */
    public void setAttributeBatchSize(final int attributeBatchSize)
    {
        this.attributeBatchSize = attributeBatchSize;
    }

    /**
     * @param userAccountInterpreter
     *            the userAccountInterpreter to set
     */
    public void setUserAccountInterpreter(final UserAccountInterpreter userAccountInterpreter)
    {
        this.userAccountInterpreter = userAccountInterpreter;
    }

    /**
     * @param attributeValueMappers
     *            the attributeValueMappers to set
     */
    public void setAttributeValueMappers(final Map<String, AttributeValueMapper> attributeValueMappers)
    {
        this.attributeValueMappers = attributeValueMappers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UserAccountInterpreter getUserAccountInterpreter()
    {
        return this.userAccountInterpreter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActive()
    {
        return this.active;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet() throws Exception
    {
        PropertyCheck.mandatory(this, "namespaceService", this.namespaceService);
        PropertyCheck.mandatory(this, "ldapInitialContextFactory", this.ldapInitialContextFactory);

        if (this.personAttributeMapping == null)
        {
            this.personAttributeMapping = new HashMap<>(5);
        }
        this.personAttributeMapping.put(ContentModel.PROP_USERNAME.toPrefixString(this.namespaceService), this.userIdAttributeName);
        this.userKeys = this.initKeys(this.personAttributeMapping);

        // Include a range restriction for the multi-valued member attribute if this is enabled
        if (this.groupAttributeMapping == null)
        {
            this.groupAttributeMapping = new HashMap<>(5);
        }
        this.groupAttributeMapping.put(ContentModel.PROP_AUTHORITY_NAME.toPrefixString(this.namespaceService), this.groupIdAttributeName);
        this.groupKeys = this.initKeys(this.groupAttributeMapping, this.attributeBatchSize > 0
                ? this.memberAttributeName + ";range=0-" + (this.attributeBatchSize - 1) : this.memberAttributeName);
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Set<QName> getPersonMappedProperties()
    {
        return this.userKeys.getSecond();
    }

    @Override
    public Collection<NodeDescription> getPersons(final Date modifiedSince)
    {
        final String query;
        if (modifiedSince == null)
        {
            query = this.personQuery;
        }
        else
        {
            final MessageFormat mf = new MessageFormat(this.personDifferentialQuery, Locale.ENGLISH);
            query = mf.format(new Object[] { this.timestampFormat.format(modifiedSince) });
        }

        final Supplier<InitialDirContext> contextSupplier = this.buildContextSupplier();
        final Function<InitialDirContext, Boolean> nextPageChecker = this.buildNextPageChecker();
        final Function<InitialDirContext, NamingEnumeration<SearchResult>> userSearcher = this.buildUserSearcher(query);

        final AtomicInteger totalEstimatedSize = new AtomicInteger(-1);
        if (this.enableProgressEstimation)
        {
            this.processQuery((result) -> {
                totalEstimatedSize.getAndIncrement();
            }, this.userSearchBase, query, new String[0]);
        }

        final NodeMapper userMapper = this.buildUserMapper();
        return new PersonCollection(contextSupplier, nextPageChecker, userSearcher, userMapper, this.queryBatchSize,
                totalEstimatedSize.get());
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getPersonNames()
    {
        final List<String> personNames = new ArrayList<>(20);
        this.processQuery((result) -> {
            final Attribute nameAttribute = result.getAttributes().get(this.userIdAttributeName);
            if (nameAttribute == null)
            {
                if (this.errorOnMissingUID)
                {
                    final Object[] params = { result.getNameInNamespace(), this.userIdAttributeName };
                    throw new AlfrescoRuntimeException("synchronization.err.ldap.get.user.id.missing", params);
                }
                else
                {
                    LOGGER.warn("User missing user id attribute DN ={}  att = {}", result.getNameInNamespace(), this.userIdAttributeName);
                }
            }
            else
            {
                final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                final String personName = attributeValues.iterator().next();
                LOGGER.debug("Person DN recognized: {}", personName);
                personNames.add(personName);
            }
        }, this.userSearchBase, this.personQuery, new String[] { this.userIdAttributeName });
        return personNames;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Collection<NodeDescription> getGroups(final Date modifiedSince)
    {
        // Work out whether the user and group trees are disjoint. This may allow us to optimize reverse DN
        // resolution.
        final LdapName groupDistinguishedNamePrefix = this.resolveDistinguishedNamePrefix(this.groupSearchBase);
        final LdapName userDistinguishedNamePrefix = this.resolveDistinguishedNamePrefix(this.userSearchBase);

        final boolean disjoint = !groupDistinguishedNamePrefix.startsWith(userDistinguishedNamePrefix)
                && !userDistinguishedNamePrefix.startsWith(groupDistinguishedNamePrefix);

        final String query;
        if (modifiedSince == null)
        {
            query = this.groupQuery;
        }
        else
        {
            final MessageFormat mf = new MessageFormat(this.groupDifferentialQuery, Locale.ENGLISH);
            query = mf.format(new Object[] { this.timestampFormat.format(modifiedSince) });
        }

        // find duplicate gid in advance
        final Set<String> groupNames = new HashSet<>();
        final Map<String, AtomicInteger> groupNameCounts = new HashMap<>();
        this.processQuery((result) -> {
            final Attribute nameAttribute = result.getAttributes().get(this.groupIdAttributeName);
            if (nameAttribute == null)
            {
                if (this.errorOnMissingUID)
                {
                    final Object[] params = { result.getNameInNamespace(), this.groupIdAttributeName };
                    throw new AlfrescoRuntimeException("synchronization.err.ldap.get.group.id.missing", params);
                }
                else
                {
                    LOGGER.warn("Missing GID on {}", result.getNameInNamespace());
                }
            }
            else
            {
                final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                final String groupName = attributeValues.iterator().next();
                LOGGER.debug("Group DN recognized: {}", groupName);

                if (groupNames.contains(groupName))
                {
                    if (this.errorOnDuplicateGID)
                    {
                        throw new AlfrescoRuntimeException("Duplicate group id found: " + groupName);
                    }
                    LOGGER.warn("Duplicate gid found for {} -> merging definitions", groupName);
                    groupNameCounts.computeIfAbsent(groupName, (x) -> {
                        return new AtomicInteger(1);
                    }).getAndIncrement();
                }
                else
                {
                    groupNames.add(groupName);
                }
            }
        }, this.groupSearchBase, this.groupQuery, new String[] { this.groupIdAttributeName });

        final Supplier<InitialDirContext> contextSupplier = this.buildContextSupplier();
        final Function<InitialDirContext, Boolean> nextPageChecker = this.buildNextPageChecker();
        final Function<InitialDirContext, NamingEnumeration<SearchResult>> groupSearcher = this.buildGroupSearcher(query);

        final NodeMapper groupMapper = this.buildGroupMapper(disjoint, groupDistinguishedNamePrefix, userDistinguishedNamePrefix);
        return new PersonCollection(contextSupplier, nextPageChecker, groupSearcher, groupMapper, this.queryBatchSize, groupNames.size());
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getGroupNames()
    {
        final List<String> groupNames = new ArrayList<>(20);
        this.processQuery((result) -> {
            final Attribute nameAttribute = result.getAttributes().get(this.groupIdAttributeName);
            if (nameAttribute == null)
            {
                if (this.errorOnMissingUID)
                {
                    final Object[] params = { result.getNameInNamespace(), this.groupIdAttributeName };
                    throw new AlfrescoRuntimeException("synchronization.err.ldap.get.group.id.missing", params);
                }
                else
                {
                    LOGGER.warn("Missing GID on {}", result.getNameInNamespace());
                }
            }
            else
            {
                final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                final String groupName = attributeValues.iterator().next();
                LOGGER.debug("Group DN recognized: {}", groupName);
                groupNames.add(groupName);
            }
        }, this.groupSearchBase, this.groupQuery, new String[] { this.groupIdAttributeName });
        return groupNames;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public String resolveDistinguishedName(final String userId, final AuthenticationDiagnostic diagnostic) throws AuthenticationException
    {
        LOGGER.debug("resolveDistinguishedName userId: {}", userId);

        final SearchControls userSearchCtls = new SearchControls();
        userSearchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);

        // Although we don't actually need any attributes, we ask for the UID for compatibility with Sun Directory Server. See ALF-3868
        userSearchCtls.setReturningAttributes(new String[] { this.userIdAttributeName });

        final String query = this.userSearchBase + "(&" + this.personQuery + "(" + this.userIdAttributeName + "= userId))";

        NamingEnumeration<SearchResult> searchResults = null;
        SearchResult result = null;

        InitialDirContext ctx = null;
        try
        {
            ctx = this.ldapInitialContextFactory.getDefaultIntialDirContext(diagnostic);

            // Execute the user query with an additional condition that ensures only the user with the required ID is
            // returned. Force RFC 2254 escaping of the user ID in the filter to avoid any manipulation

            searchResults = ctx.search(this.userSearchBase, "(&" + this.personQuery + "(" + this.userIdAttributeName + "={0}))",
                    new Object[] { userId }, userSearchCtls);

            if (searchResults.hasMore())
            {
                result = searchResults.next();
                final Attributes attributes = result.getAttributes();
                final Attribute uidAttribute = attributes.get(this.userIdAttributeName);
                if (uidAttribute == null)
                {
                    if (this.errorOnMissingUID)
                    {
                        throw new AlfrescoRuntimeException(
                                "User returned by user search does not have mandatory user id attribute " + attributes);
                    }
                    else
                    {
                        LOGGER.warn("User returned by user search does not have mandatory user id attribute {}", attributes);
                    }
                }
                // MNT:2597 We don't trust the LDAP server's treatment of whitespace, accented characters etc. We will
                // only resolve this user if the user ID matches
                else if (userId.equalsIgnoreCase((String) uidAttribute.get(0)))
                {
                    final String name = result.getNameInNamespace();

                    this.commonCloseSearchResult(result);
                    result = null;
                    return name;
                }

                this.commonCloseSearchResult(result);
                result = null;
            }

            final Object[] args = { userId, query };
            diagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_LOOKUP_USER, false, args);

            throw new AuthenticationException("authentication.err.connection.ldap.user.notfound", args, diagnostic);
        }
        catch (final NamingException e)
        {
            // Connection is good here - AuthenticationException would be thrown by ldapInitialContextFactory
            final Object[] args1 = { userId, query };
            diagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_SEARCH, false, args1);

            // failed to search
            final Object[] args = { e.getLocalizedMessage() };
            throw new AuthenticationException("authentication.err.connection.ldap.search", diagnostic, args, e);
        }
        finally
        {
            this.commonAfterQueryCleanup(searchResults, result, ctx);
        }
    }

    protected void commonCloseSearchResult(final SearchResult result) throws NamingException
    {
        // Close the contexts, see ALF-20682
        final Context context = (Context) result.getObject();
        if (context != null)
        {
            context.close();
        }
    }

    protected void commonAfterQueryCleanup(final NamingEnumeration<SearchResult> searchResults, final SearchResult result,
            final InitialDirContext ctx)
    {
        if (result != null)
        {
            try
            {
                this.commonCloseSearchResult(result);
            }
            catch (final NamingException e)
            {
                LOGGER.debug("Error when closing result block context", e);
            }
        }
        if (searchResults != null)
        {
            try
            {
                searchResults.close();
            }
            catch (final NamingException e)
            {
                LOGGER.debug("Error when closing searchResults context", e);
            }
        }
        if (ctx != null)
        {
            try
            {
                ctx.close();
            }
            catch (final NamingException e)
            {
                LOGGER.debug("Error when closing ldap context", e);
            }
        }
    }

    protected Pair<String[], Set<QName>> initKeys(final Map<String, String> attributeMapping, final String... extraAttibutes)
    {
        // Compile a complete array of LDAP attribute names, including operational attributes
        final Set<String> attributeSet = new TreeSet<>();
        attributeSet.addAll(Arrays.asList(extraAttibutes));
        attributeSet.add(this.modifyTimestampAttributeName);
        for (final String attribute : attributeMapping.values())
        {
            if (attribute != null)
            {
                attributeSet.add(attribute);
            }
        }
        final String[] attributeNames = new String[attributeSet.size()];
        attributeSet.toArray(attributeNames);

        // Create a set with the property names converted to QNames
        final Set<QName> qnames = new HashSet<>(attributeMapping.size() * 2);
        for (final String property : attributeMapping.keySet())
        {
            qnames.add(QName.createQName(property, this.namespaceService));
        }

        return new Pair<>(attributeNames, qnames);
    }

    protected LdapName resolveDistinguishedNamePrefix(final String searchBase)
    {
        final String searchBaseLower = searchBase.toLowerCase(Locale.ENGLISH);
        try
        {
            return fixedLdapName(searchBaseLower);
        }
        catch (final InvalidNameException e)
        {
            final Object[] params = { searchBaseLower, e.getLocalizedMessage() };
            throw new AlfrescoRuntimeException("synchronization.err.ldap.search.base.invalid", params, e);
        }
    }

    /**
     * Converts a given DN into one suitable for use through JNDI. In particular, escapes special characters such as '/'
     * which have special meaning to JNDI.
     *
     * @param dn
     *            the dn
     * @return the name
     * @throws InvalidNameException
     *             the invalid name exception
     */
    protected static Name jndiName(final String dn) throws InvalidNameException
    {
        final Name n = new CompositeName();
        n.add(dn);
        return n;
    }

    /**
     * Works around a bug in the JDK DN parsing. If an RDN has trailing escaped whitespace in the format "\\20" then
     * LdapName would normally strip this. This method works around this by replacing "\\20" with "\\ " and "\\0D" with
     * "\\\r".
     *
     * @param dn
     *            the DN
     * @return the parsed ldap name
     * @throws InvalidNameException
     *             if the DN is invalid
     */
    protected static LdapName fixedLdapName(final String dn) throws InvalidNameException
    {
        // Optimization for DNs without escapes in them
        if (dn.indexOf('\\') == -1)
        {
            return new LdapName(dn);
        }

        final StringBuilder fixed = new StringBuilder(dn.length());
        final int length = dn.length();
        for (int i = 0; i < length; i++)
        {
            final char c = dn.charAt(i);
            char c1, c2;
            if (c == '\\')
            {
                if (i + 2 < length && Character.isLetterOrDigit(c1 = dn.charAt(i + 1)) && Character.isLetterOrDigit(c2 = dn.charAt(i + 2)))
                {
                    if (c1 == '2' && c2 == '0')
                    {
                        fixed.append("\\ ");
                    }
                    else if (c1 == '0' && c2 == 'D')
                    {
                        fixed.append("\\\r");
                    }
                    else
                    {
                        fixed.append(dn, i, i + 3);
                    }
                    i += 2;
                }
                else if (i + 1 < length)
                {
                    fixed.append(dn, i, i + 2);
                    i += 1;
                }
                else
                {
                    fixed.append(c);
                }
            }
            else
            {
                fixed.append(c);
            }
        }
        return new LdapName(fixed.toString());
    }

    /**
     * Invokes the given callback on each entry returned by the given query.
     *
     * @param callback
     *            the callback
     * @param searchBase
     *            the base DN for the search
     * @param query
     *            the query
     * @param returningAttributes
     *            the attributes to include in search results
     * @throws AlfrescoRuntimeException
     */
    protected void processQuery(final SearchCallback callback, final String searchBase, final String query,
            final String[] returningAttributes)
    {
        final SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setReturningAttributes(returningAttributes);

        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug(
                    "Processing query\nSearch base: {}\n\rReturn result limit: {}\n\tDereflink: {}\n\rReturn named object: {}\n\tTime limit for search: {}\n\tAttributes to return: {} items\n\tAttributes: {}",
                    searchBase, searchControls.getCountLimit(), searchControls.getDerefLinkFlag(), searchControls.getReturningObjFlag(),
                    searchControls.getTimeLimit(), String.valueOf(returningAttributes.length), Arrays.toString(returningAttributes));
        }

        InitialDirContext ctx = null;
        NamingEnumeration<SearchResult> searchResults = null;
        SearchResult result = null;
        try
        {
            ctx = this.ldapInitialContextFactory.getDefaultIntialDirContext(this.queryBatchSize);
            do
            {
                searchResults = ctx.search(searchBase, query, searchControls);

                while (searchResults.hasMore())
                {
                    result = searchResults.next();
                    callback.process(result);

                    this.commonCloseSearchResult(result);
                    result = null;
                }
            }
            while (this.ldapInitialContextFactory.hasNextPage(ctx, this.queryBatchSize));
        }
        catch (final NamingException e)
        {
            final Object[] params = { e.getLocalizedMessage() };
            throw new AlfrescoRuntimeException("synchronization.err.ldap.search", params, e);
        }
        catch (final ParseException e)
        {
            final Object[] params = { e.getLocalizedMessage() };
            throw new AlfrescoRuntimeException("synchronization.err.ldap.search", params, e);
        }
        finally
        {
            this.commonAfterQueryCleanup(searchResults, result, ctx);
        }
    }

    /**
     * Does a case-insensitive search for the given value in an attribute.
     *
     * @param attribute
     *            the attribute
     * @param value
     *            the value to search for
     * @return <code>true</code>, if the value was found
     * @throws NamingException
     *             if there is a problem accessing the attribute values
     */
    protected boolean hasAttributeValue(final Attribute attribute, final String value) throws NamingException
    {
        if (attribute != null)
        {
            final NamingEnumeration<?> values = attribute.getAll();
            while (values.hasMore())
            {
                final Object mappedValue = this.mapAttributeValue(attribute.getID(), values.next());
                if (mappedValue instanceof String && value.equalsIgnoreCase((String) mappedValue))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets the values of a repeating attribute that may have range restriction options. If an attribute is range
     * restricted, it will appear in the attribute set with a ";range=i-j" option, where i and j indicate the start and
     * end index, and j is '*' if it is at the end.
     *
     * @param attributes
     *            the attributes
     * @param attributeName
     *            the attribute name
     * @return the range restricted attribute
     * @throws NamingException
     *             the naming exception
     */
    protected Attribute getRangeRestrictedAttribute(final Attributes attributes, final String attributeName) throws NamingException
    {
        final Attribute unrestricted = attributes.get(attributeName);
        if (unrestricted != null)
        {
            return unrestricted;
        }
        final NamingEnumeration<? extends Attribute> i = attributes.getAll();
        final String searchString = attributeName.toLowerCase(Locale.ENGLISH) + ';';
        while (i.hasMore())
        {
            final Attribute attribute = i.next();
            if (attribute.getID().toLowerCase(Locale.ENGLISH).startsWith(searchString))
            {
                return attribute;
            }
        }
        return null;
    }

    protected Supplier<InitialDirContext> buildContextSupplier()
    {
        return () -> {
            final InitialDirContext ctx = this.ldapInitialContextFactory.getDefaultIntialDirContext(this.queryBatchSize);
            return ctx;
        };
    }

    protected Function<InitialDirContext, Boolean> buildNextPageChecker()
    {
        return (ctxt) -> {
            final boolean hasNextPage = this.ldapInitialContextFactory.hasNextPage(ctxt, this.queryBatchSize);
            return Boolean.valueOf(hasNextPage);
        };
    }

    protected Function<InitialDirContext, NamingEnumeration<SearchResult>> buildUserSearcher(final String query)
    {
        final SearchControls userSearchCtls = new SearchControls();
        userSearchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        userSearchCtls.setReturningAttributes(this.userKeys.getFirst());
        // MNT-14001 fix, set search limit to ensure that server will not return more search results then provided by paged result control
        userSearchCtls.setCountLimit(this.queryBatchSize > 0 ? this.queryBatchSize : 0);

        return (ctx) -> {
            try
            {
                final NamingEnumeration<SearchResult> results = ctx.search(this.userSearchBase, query, userSearchCtls);
                return results;
            }
            catch (final NamingException e)
            {
                throw new AlfrescoRuntimeException("Failed to import people.", e);
            }
        };
    }

    protected NodeMapper buildUserMapper()
    {
        return (searchResult) -> {
            return this.mapToNode(searchResult, this.userIdAttributeName, this.personAttributeMapping, this.personAttributeDefaults);
        };
    }

    protected Function<InitialDirContext, NamingEnumeration<SearchResult>> buildGroupSearcher(final String query)
    {
        final SearchControls userSearchCtls = new SearchControls();
        userSearchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        userSearchCtls.setReturningAttributes(this.groupKeys.getFirst());
        // MNT-14001 fix, set search limit to ensure that server will not return more search results then provided by paged result control
        userSearchCtls.setCountLimit(this.queryBatchSize > 0 ? this.queryBatchSize : 0);

        return (ctx) -> {
            try
            {
                final NamingEnumeration<SearchResult> results = ctx.search(this.groupSearchBase, query, userSearchCtls);
                return results;
            }
            catch (final NamingException e)
            {
                throw new AlfrescoRuntimeException("Failed to import groups.", e);
            }
        };
    }

    protected NodeMapper buildGroupMapper(final boolean disjoint, final LdapName groupDistinguishedNamePrefix,
            final LdapName userDistinguishedNamePrefix)
    {
        return (searchResult) -> {
            final UidNodeDescription nodeDescription = this.mapToNode(searchResult, this.groupIdAttributeName, this.groupAttributeMapping,
                    this.groupAttributeDefaults);

            final String groupName = AuthorityType.GROUP.getPrefixString() + nodeDescription.getId();
            nodeDescription.getProperties().put(ContentModel.PROP_AUTHORITY_NAME, groupName);

            final Collection<String> groupChildren = this.lookupGroupChildren(searchResult, groupName, disjoint,
                    groupDistinguishedNamePrefix, userDistinguishedNamePrefix);
            nodeDescription.getChildAssociations().addAll(groupChildren);

            return nodeDescription;
        };
    }

    protected Collection<String> lookupGroupChildren(final SearchResult searchResult, final String gid, final boolean disjoint,
            final LdapName groupDistinguishedNamePrefix, final LdapName userDistinguishedNamePrefix) throws NamingException
    {
        final InitialDirContext ctx = this.ldapInitialContextFactory.getDefaultIntialDirContext();
        try
        {
            LOGGER.debug("Processing group: {}, from source: {}", gid, searchResult.getNameInNamespace());

            final Collection<String> children = new HashSet<>();

            final Attributes attributes = searchResult.getAttributes();
            Attribute memAttribute = this.getRangeRestrictedAttribute(attributes, this.memberAttributeName);
            int nextStart = this.attributeBatchSize;

            while (memAttribute != null)
            {
                for (int i = 0; i < memAttribute.size(); i++)
                {
                    final String attribute = (String) memAttribute.get(i);
                    if (attribute != null && attribute.length() > 0)
                    {
                        try
                        {
                            // Attempt to parse the member attribute as a DN. If this fails we have a fallback
                            // in the catch block
                            final LdapName distinguishedNameForComparison = fixedLdapName(attribute.toLowerCase(Locale.ENGLISH));
                            Attribute nameAttribute;

                            // If the user and group search bases are different we may be able to recognize user
                            // and group DNs without a secondary lookup
                            if (disjoint)
                            {
                                final LdapName distinguishedName = fixedLdapName(attribute);
                                final Attributes nameAttributes = distinguishedName.getRdn(distinguishedName.size() - 1).toAttributes();

                                // Recognize user DNs
                                if (distinguishedNameForComparison.startsWith(userDistinguishedNamePrefix)
                                        && (nameAttribute = nameAttributes.get(this.userIdAttributeName)) != null)
                                {
                                    final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                                    final String personName = attributeValues.iterator().next();
                                    LOGGER.debug("User DN recognized: {}", personName);
                                    children.add(personName);
                                    continue;
                                }

                                // Recognize group DNs
                                if (distinguishedNameForComparison.startsWith(groupDistinguishedNamePrefix)
                                        && (nameAttribute = nameAttributes.get(this.groupIdAttributeName)) != null)
                                {
                                    final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                                    final String groupName = attributeValues.iterator().next();
                                    LOGGER.debug("Group DN recognized: {}{}", AuthorityType.GROUP.getPrefixString(), groupName);
                                    children.add(AuthorityType.GROUP.getPrefixString() + groupName);
                                    continue;
                                }
                            }

                            // If we can't determine the name and type from the DN alone, try a directory lookup
                            if (distinguishedNameForComparison.startsWith(userDistinguishedNamePrefix)
                                    || distinguishedNameForComparison.startsWith(groupDistinguishedNamePrefix))
                            {
                                try
                                {
                                    final Attributes childAttributes = ctx.getAttributes(jndiName(attribute),
                                            new String[] { "objectclass", this.groupIdAttributeName, this.userIdAttributeName });
                                    final Attribute objectClass = childAttributes.get("objectclass");
                                    if (this.hasAttributeValue(objectClass, this.personType))
                                    {
                                        nameAttribute = childAttributes.get(this.userIdAttributeName);
                                        if (nameAttribute == null)
                                        {
                                            if (this.errorOnMissingUID)
                                            {
                                                throw new AlfrescoRuntimeException("User missing user id attribute DN =" + attribute
                                                        + "  att = " + this.userIdAttributeName);
                                            }
                                            else
                                            {
                                                LOGGER.warn("User missing user id attribute DN =" + attribute + "  att = "
                                                        + this.userIdAttributeName);
                                                continue;
                                            }
                                        }

                                        final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                                        final String personName = attributeValues.iterator().next();

                                        LOGGER.debug("User DN recognized by directory lookup: {}", personName);
                                        children.add(personName);
                                        continue;
                                    }
                                    else if (this.hasAttributeValue(objectClass, this.groupType))
                                    {
                                        nameAttribute = childAttributes.get(this.groupIdAttributeName);
                                        if (nameAttribute == null)
                                        {
                                            if (this.errorOnMissingGID)
                                            {
                                                final Object[] params = { searchResult.getNameInNamespace(), this.groupIdAttributeName };
                                                throw new AlfrescoRuntimeException("synchronization.err.ldap.get.group.id.missing", params);
                                            }
                                            else
                                            {
                                                LOGGER.warn("Missing GID on {}", childAttributes);
                                                continue;
                                            }
                                        }

                                        final Collection<String> attributeValues = this.mapAttribute(nameAttribute, String.class);
                                        final String groupName = attributeValues.iterator().next();
                                        LOGGER.debug("Group DN recognized by directory lookup: {}{}", AuthorityType.GROUP.getPrefixString(),
                                                groupName);
                                        children.add(AuthorityType.GROUP.getPrefixString() + groupName);
                                        continue;
                                    }
                                }
                                catch (final NamingException e)
                                {
                                    // Unresolvable name
                                    if (this.errorOnMissingMembers)
                                    {
                                        final Object[] params = { gid, attribute, e.getLocalizedMessage() };
                                        throw new AlfrescoRuntimeException("synchronization.err.ldap.group.member.missing.exception",
                                                params, e);
                                    }
                                    LOGGER.warn("Failed to resolve member of group '{}, ' with distinguished name: {}", gid, attribute, e);
                                    continue;
                                }
                            }
                            if (this.errorOnMissingMembers)
                            {
                                final Object[] params = { gid, attribute };
                                throw new AlfrescoRuntimeException("synchronization.err.ldap.group.member.missing", params);
                            }
                            LOGGER.warn("Failed to resolve member of group '{}' with distinguished name: {}", gid, attribute);
                        }
                        catch (final InvalidNameException e)
                        {
                            // The member attribute didn't parse as a DN. So assume we have a group class like
                            // posixGroup (FDS) that directly lists user names
                            LOGGER.debug("Member DN recognized as posixGroup: {}", attribute);
                            children.add(attribute);
                        }
                    }
                }

                // If we are using attribute matching and we haven't got to the end (indicated by an asterisk),
                // fetch the next batch
                if (nextStart > 0 && !PATTERN_RANGE_END.matcher(memAttribute.getID().toLowerCase(Locale.ENGLISH)).find())
                {
                    final Attributes childAttributes = ctx.getAttributes(jndiName(searchResult.getNameInNamespace()), new String[] {
                            this.memberAttributeName + ";range=" + nextStart + '-' + (nextStart + this.attributeBatchSize - 1) });
                    memAttribute = this.getRangeRestrictedAttribute(childAttributes, this.memberAttributeName);
                    nextStart += this.attributeBatchSize;
                }
                else
                {
                    memAttribute = null;
                }
            }

            return children;
        }
        finally
        {
            this.commonAfterQueryCleanup(null, null, ctx);
        }
    }

    protected UidNodeDescription mapToNode(final SearchResult searchResult, final String idAttributeName,
            final Map<String, String> attributeMapping, final Map<String, String> attributeDefaults) throws NamingException
    {
        final Attributes attributes = searchResult.getAttributes();
        final Collection<String> uidValues = this.mapAttribute(attributes.get(idAttributeName), String.class);
        final String uid = uidValues.iterator().next();

        final UidNodeDescription nodeDescription = new UidNodeDescription(searchResult.getNameInNamespace(), uid);

        final Attribute modifyTimestamp = attributes.get(this.modifyTimestampAttributeName);
        if (modifyTimestamp != null)
        {
            try
            {
                nodeDescription.setLastModified(this.timestampFormat.parse(modifyTimestamp.get().toString()));
            }
            catch (final ParseException e)
            {
                throw new AlfrescoRuntimeException("Failed to parse timestamp.", e);
            }
        }

        final PropertyMap properties = nodeDescription.getProperties();
        for (final String key : attributeMapping.keySet())
        {
            final QName keyQName = QName.createQName(key, this.namespaceService);

            final String attributeName = attributeMapping.get(key);
            if (attributeName != null)
            {
                final Attribute attribute = attributes.get(attributeName);
                final String defaultAttribute = attributeDefaults.get(key);

                if (attribute != null)
                {
                    final Collection<Object> mappedAttributeValue = this.mapAttribute(attribute);
                    if (mappedAttributeValue.size() == 1)
                    {
                        final Object singleValue = mappedAttributeValue.iterator().next();
                        if (singleValue instanceof Serializable)
                        {
                            properties.put(keyQName, (Serializable) singleValue);
                        }
                        else
                        {
                            properties.put(keyQName, DefaultTypeConverter.INSTANCE.convert(String.class, singleValue));
                        }
                    }
                    else if (!mappedAttributeValue.isEmpty())
                    {
                        final ArrayList<Serializable> values = new ArrayList<>();
                        mappedAttributeValue.forEach((x) -> {
                            if (x instanceof Serializable)
                            {
                                values.add((Serializable) x);
                            }
                            else
                            {
                                values.add(DefaultTypeConverter.INSTANCE.convert(String.class, x));
                            }
                        });
                        properties.put(keyQName, values);
                    }
                    else if (defaultAttribute != null)
                    {
                        properties.put(keyQName, defaultAttribute);
                    }
                    else
                    {
                        // Make sure that a 2nd sync, updates deleted ldap attributes (MNT-14026)
                        properties.put(keyQName, null);
                    }
                }
                else if (defaultAttribute != null)
                {
                    properties.put(keyQName, defaultAttribute);
                }
                else
                {
                    // Make sure that a 2nd sync, updates deleted ldap attributes (MNT-14026)
                    properties.put(keyQName, null);
                }
            }
            else
            {
                final String defaultValue = attributeDefaults.get(key);
                if (defaultValue != null)
                {
                    properties.put(keyQName, defaultValue);
                }
            }
        }

        return nodeDescription;
    }

    protected <T> Collection<T> mapAttribute(final Attribute attribute, final Class<T> expectedValueClass) throws NamingException
    {
        Collection<T> values;
        if (attribute.isOrdered())
        {
            values = new ArrayList<>();
        }
        else
        {
            values = new HashSet<>();
        }
        final NamingEnumeration<?> allAttributeValues = attribute.getAll();
        while (allAttributeValues.hasMore())
        {
            final Object next = allAttributeValues.next();
            final Object mappedValue = this.mapAttributeValue(attribute.getID(), next);
            final T value = DefaultTypeConverter.INSTANCE.convert(expectedValueClass, mappedValue);
            values.add(value);
        }

        LOGGER.debug("Mapped value of {} to {}", attribute, values);

        return values;
    }

    protected Collection<Object> mapAttribute(final Attribute attribute) throws NamingException
    {
        Collection<Object> values;
        if (attribute.isOrdered())
        {
            values = new ArrayList<>();
        }
        else
        {
            values = new HashSet<>();
        }
        final NamingEnumeration<?> allAttributeValues = attribute.getAll();
        while (allAttributeValues.hasMore())
        {
            final Object next = allAttributeValues.next();
            final Object mappedValue = this.mapAttributeValue(attribute.getID(), next);
            values.add(mappedValue);
        }

        LOGGER.debug("Mapped value of {} to {}", attribute, values);

        return values;
    }

    protected Object mapAttributeValue(final String attributeId, final Object value)
    {
        final AttributeValueMapper mapper = this.attributeValueMappers != null ? this.attributeValueMappers.get(attributeId) : null;
        Object mappedValue;
        if (mapper != null)
        {
            LOGGER.trace("Using {} to map value {} of attribute {}", mapper, value, attributeId);
            mappedValue = mapper.mapAttributeValue(attributeId, value);
        }
        else
        {
            mappedValue = value;
        }
        return mappedValue;
    }
}
