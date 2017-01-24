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

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.naming.NamingEnumeration;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchResult;

import org.alfresco.repo.security.sync.NodeDescription;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class GroupCollection extends AbstractCollection<NodeDescription>
{

    protected final Supplier<InitialDirContext> ctxSupplier;

    protected final Function<InitialDirContext, Boolean> nextPageChecker;

    protected final Function<InitialDirContext, NamingEnumeration<SearchResult>> searcher;

    protected final NodeMapper nodeMapper;

    protected final int batchSize;

    /** The total estimated size. */
    protected final int totalEstimatedSize;

    public GroupCollection(final Supplier<InitialDirContext> ctxSupplier, final Function<InitialDirContext, Boolean> nextPageChecker,
            final Function<InitialDirContext, NamingEnumeration<SearchResult>> searcher,
            final NodeMapper nodeMapper, final int batchSize, final int totalEstimatedSize)
    {
        this.ctxSupplier = ctxSupplier;
        this.nextPageChecker = nextPageChecker;
        this.searcher = searcher;
        this.nodeMapper = nodeMapper;
        this.batchSize = batchSize;

        this.totalEstimatedSize = totalEstimatedSize;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Iterator<NodeDescription> iterator()
    {
        return new GroupIterator(this.ctxSupplier, this.nextPageChecker, this.searcher, this.nodeMapper, this.batchSize);
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public int size()
    {
        return this.totalEstimatedSize;
    }
}
