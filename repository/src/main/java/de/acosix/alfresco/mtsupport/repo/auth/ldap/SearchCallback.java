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

import java.text.ParseException;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.SearchResult;

import org.slf4j.LoggerFactory;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
@FunctionalInterface
public interface SearchCallback
{

    /**
     * Processes the given search result.
     *
     * @param result
     *            the result
     * @throws NamingException
     *             on naming exceptions
     * @throws ParseException
     *             on parse exceptions
     */
    public default void process(final SearchResult result) throws NamingException, ParseException
    {
        try
        {
            doProcess(result);
        }
        finally
        {
            final Object obj = result.getObject();

            if (obj != null && obj instanceof Context)
            {
                try
                {
                    ((Context) obj).close();
                }
                catch (final NamingException e)
                {
                    LoggerFactory.getLogger(SearchCallback.class).debug("error when closing result block context", e);
                }
            }
        }
    }

    public void doProcess(SearchResult result) throws NamingException, ParseException;
}
