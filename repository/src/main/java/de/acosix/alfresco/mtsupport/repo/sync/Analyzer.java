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

import org.alfresco.repo.batch.BatchProcessor.BatchProcessWorker;
import org.alfresco.repo.security.sync.NodeDescription;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public interface Analyzer extends BatchProcessWorker<NodeDescription>
{

    /**
     * Retrieves the timestamp of the latest group to be modified.
     *
     * @return the timestamp of the latest group modification
     */
    long getLatestModified();

    /**
     * @return the groupsToCreate
     */
    Map<String, String> getGroupsToCreate();

    /**
     * @return the personParentsToAdd
     */
    Map<String, Set<String>> getUserParentsToAdd();

    /**
     * @return the personParentsToRemove
     */
    Map<String, Set<String>> getUserParentsToRemove();

    /**
     * @return the groupParentsToAdd
     */
    Map<String, Set<String>> getGroupParentsToAdd();

    /**
     * @return the groupParentsToRemove
     */
    Map<String, Set<String>> getGroupParentsToRemove();
}
