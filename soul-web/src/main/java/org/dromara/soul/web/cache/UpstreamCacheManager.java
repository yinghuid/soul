/*
 *   Licensed to the Apache Software Foundation (ASF) under one or more
 *   contributor license agreements.  See the NOTICE file distributed with
 *   this work for additional information regarding copyright ownership.
 *   The ASF licenses this file to You under the Apache License, Version 2.0
 *   (the "License"); you may not use this file except in compliance with
 *   the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package org.dromara.soul.web.cache;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections4.CollectionUtils;
import org.dromara.soul.common.dto.convert.DivideUpstream;
import org.dromara.soul.common.dto.zk.SelectorZkDTO;
import org.dromara.soul.common.utils.GSONUtils;
import org.dromara.soul.common.utils.UrlUtils;
import org.dromara.soul.web.concurrent.SoulThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * this is divide  http url upstream.
 *
 * @author xiaoyu
 */
@Component
public class UpstreamCacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamCacheManager.class);

    private static final BlockingQueue<SelectorZkDTO> BLOCKING_QUEUE = new LinkedBlockingQueue<>(1024);

    private static final int MAX_THREAD = Runtime.getRuntime().availableProcessors() << 1;

    private static final Map<String, List<DivideUpstream>> UPSTREAM_MAP = Maps.newConcurrentMap();

    /**
     * Find upstream list by selector id list.
     *
     * @param selectorId the selector id
     * @return the list
     */
    public List<DivideUpstream> findUpstreamListBySelectorId(final String selectorId) {
        return UPSTREAM_MAP.get(selectorId);
    }

    /**
     * Remove by key.
     *
     * @param key the key
     */
    static void removeByKey(final String key) {
        UPSTREAM_MAP.remove(key);
    }

    /**
     * Init.
     */
    @PostConstruct
    public void init() {
        synchronized (LOGGER) {
            ExecutorService executorService = new ThreadPoolExecutor(MAX_THREAD, MAX_THREAD,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    SoulThreadFactory.create("divide-upstream-task",
                            false));
            for (int i = 0; i < MAX_THREAD; i++) {
                executorService.execute(new Worker());
            }
        }
    }


    /**
     * Submit.
     *
     * @param selectorZkDTO the selector zk dto
     */
    static void submit(final SelectorZkDTO selectorZkDTO) {
        try {
            BLOCKING_QUEUE.put(selectorZkDTO);
        } catch (InterruptedException e) {
            LOGGER.error(e.getMessage());
        }
    }

    /**
     * Execute.
     *
     * @param selectorZkDTO the selector zk dto
     */
    public void execute(final SelectorZkDTO selectorZkDTO) {
        final List<DivideUpstream> upstreamList =
                GSONUtils.getInstance().fromList(selectorZkDTO.getHandle(), DivideUpstream[].class);
        if (CollectionUtils.isNotEmpty(upstreamList)) {
            List<DivideUpstream> resultList = Lists.newArrayListWithCapacity(upstreamList.size());
            for (DivideUpstream divideUpstream : upstreamList) {
                final boolean pass = UrlUtils.checkUrl(divideUpstream.getUpstreamUrl());
                if (pass) {
                    resultList.add(divideUpstream);
                }
            }
            UPSTREAM_MAP.put(selectorZkDTO.getId(), resultList);
        }
    }

    /**
     * The type Worker.
     */
    class Worker implements Runnable {

        @Override
        public void run() {
            runTask();
        }

        private void runTask() {
            while (true) {
                try {
                    final SelectorZkDTO selectorZkDTO = BLOCKING_QUEUE.take();
                    Optional.of(selectorZkDTO).ifPresent(UpstreamCacheManager.this::execute);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
