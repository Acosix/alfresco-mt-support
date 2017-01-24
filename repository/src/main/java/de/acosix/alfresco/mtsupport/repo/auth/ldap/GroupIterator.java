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

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsResponseControl;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.repo.security.sync.NodeDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class GroupIterator implements Iterator<NodeDescription>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(GroupIterator.class);

    protected final Supplier<InitialDirContext> ctxSupplier;

    protected final Function<InitialDirContext, Boolean> nextPageChecker;

    protected final Function<InitialDirContext, NamingEnumeration<SearchResult>> searcher;

    protected final NodeMapper nodeMapper;

    protected final int batchSize;

    /** The directory context. */
    protected InitialDirContext ctx;

    /** Paged result response control retrieved from ldap server */
    protected PagedResultsResponseControl pagedResultsResponseControl;

    protected final Map<String, AtomicInteger> duplicateGroupRemainingCounts = new HashMap<>();

    protected final Map<String, NodeDescription> mergedGroupDescriptions = new HashMap<>();

    /** The search results. */
    protected NamingEnumeration<SearchResult> searchResults;

    /** The next node description to return. */
    protected NodeDescription next;

    /**
     * Instantiates a new person iterator.
     */
    public GroupIterator(final Supplier<InitialDirContext> ctxSupplier, final Function<InitialDirContext, Boolean> nextPageChecker,
            final Function<InitialDirContext, NamingEnumeration<SearchResult>> searcher,
            final NodeMapper nodeMapper, final int batchSize)
    {
        this.ctxSupplier = ctxSupplier;
        this.nextPageChecker = nextPageChecker;
        this.searcher = searcher;
        this.nodeMapper = nodeMapper;
        this.batchSize = batchSize;

        this.ctx = this.ctxSupplier.get();

        try
        {
            this.next = this.fetchNext();
        }
        catch (final NamingException e)
        {
            throw new AlfrescoRuntimeException("Failed to import people.", e);
        }
        finally
        {
            if (this.searchResults == null)
            {
                this.closeContext();
            }
        }
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext()
    {
        return this.next != null;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public NodeDescription next()
    {
        if (this.next == null)
        {
            throw new IllegalStateException();
        }

        final NodeDescription current = this.next;
        try
        {
            this.next = this.fetchNext();
        }
        catch (final NamingException e)
        {
            throw new AlfrescoRuntimeException("Failed to import groups.", e);
        }
        return current;
    }

    /**
     * Pre-fetches the next node description to be returned.
     *
     * @return the node description
     * @throws NamingException
     *             on a naming exception
     */
    protected NodeDescription fetchNext() throws NamingException
    {
        Boolean readyForNextPage;
        do
        {
            readyForNextPage = Boolean.valueOf(this.searchResults == null);
            while (!Boolean.TRUE.equals(readyForNextPage) && this.searchResults.hasMore())
            {
                final SearchResult result = this.searchResults.next();
                final UidNodeDescription nodeDescription = this.nodeMapper.mapToNode(result);
                final String gid = nodeDescription.getId();

                final AtomicInteger remainingCount = this.duplicateGroupRemainingCounts.getOrDefault(gid, new AtomicInteger(1));
                if (remainingCount.getAndDecrement() == 1)
                {
                    LOGGER.debug("Adding group for {}", gid);

                    final Object obj = result.getObject();
                    if (obj != null && obj instanceof Context)
                    {
                        ((Context) obj).close();
                    }

                    final NodeDescription mergedNodeDescription = this.mergedGroupDescriptions.get(gid);
                    if (mergedNodeDescription != null)
                    {
                        mergedNodeDescription.getChildAssociations().addAll(nodeDescription.getChildAssociations());
                        mergedNodeDescription.getProperties().putAll(nodeDescription.getProperties());

                        final Date lastModified1 = mergedNodeDescription.getLastModified();
                        final Date lastModified2 = nodeDescription.getLastModified();
                        if (lastModified2.after(lastModified1))
                        {
                            mergedNodeDescription.setLastModified(lastModified2);
                        }

                        return mergedNodeDescription;
                    }

                    return nodeDescription;
                }
                else
                {
                    this.mergedGroupDescriptions.put(gid, nodeDescription);
                }
            }

            // Examine the paged results control response for an indication that another page is available
            if (!Boolean.TRUE.equals(readyForNextPage))
            {
                readyForNextPage = this.nextPageChecker.apply(this.ctx);

                if (Boolean.TRUE.equals(readyForNextPage))
                {
                    // MNT-14001 fix, next page available - remember last paged results control
                    // using cookie from this control we can restart search from current position if needed
                    final LdapContext ldapContext = (LdapContext) this.ctx;
                    final Control[] controls = ldapContext.getResponseControls();

                    if (controls != null)
                    {
                        for (final Control control : controls)
                        {
                            if (control instanceof PagedResultsResponseControl)
                            {
                                this.pagedResultsResponseControl = (PagedResultsResponseControl) control;
                            }
                        }
                    }
                }
            }

            // Fetch the next page if there is one
            if (Boolean.TRUE.equals(readyForNextPage))
            {
                this.searchResults = this.searcher.apply(this.ctx);
            }
        }
        while (Boolean.TRUE.equals(readyForNextPage));

        this.closeResultSet();
        this.closeContext();

        return null;
    }

    protected void closeContext()
    {
        if (this.ctx != null)
        {
            try
            {
                this.ctx.close();
            }
            catch (final NamingException e)
            {
                LOGGER.debug("Error when closing ldap context", e);
            }
            this.ctx = null;
        }
    }

    protected void closeResultSet()
    {
        if (this.searchResults != null)
        {
            try
            {
                this.searchResults.close();
            }
            catch (final NamingException e)
            {
                LOGGER.debug("Error when closing searchResults context", e);
            }
            this.searchResults = null;
        }
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void remove()
    {
        throw new UnsupportedOperationException();
    }
}
