/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.management.operation;

import com.hazelcast.config.MapConfig;
import com.hazelcast.management.MapConfigAdapter;
import com.hazelcast.map.MapService;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.Operation;

import java.io.IOException;

/**
 * User: sancar
 * Date: 3/27/13
 * Time: 10:05 AM
 */
public class UpdateMapConfigOperation extends Operation {

    private String mapName;
    private MapConfig mapConfig;

    public UpdateMapConfigOperation() {

    }

    public UpdateMapConfigOperation(String mapName, MapConfig mapConfig) {
        this.mapName = mapName;
        this.mapConfig = mapConfig;
    }

    public void beforeRun() throws Exception {
    }

    public void run() throws Exception {
        MapService service = getService();
        MapConfig config = service.getMapContainer(mapName).getMapConfig();
        config.setTimeToLiveSeconds(mapConfig.getTimeToLiveSeconds());
        config.setMaxIdleSeconds(mapConfig.getMaxIdleSeconds());
        config.setEvictionPolicy(mapConfig.getEvictionPolicy());
        config.setEvictionPercentage(mapConfig.getEvictionPercentage());
        config.setReadBackupData(mapConfig.isReadBackupData());
        config.setBackupCount(mapConfig.getTotalBackupCount());
        config.setAsyncBackupCount(mapConfig.getAsyncBackupCount());
        config.setMaxSizeConfig(mapConfig.getMaxSizeConfig());
    }

    public void afterRun() throws Exception {
    }

    public boolean returnsResponse() {
        return true;
    }

    public Object getResponse() {
        return null;
    }

    protected void writeInternal(ObjectDataOutput out) throws IOException {
        out.writeUTF(mapName);
        new MapConfigAdapter(mapConfig).writeData(out);
    }

    protected void readInternal(ObjectDataInput in) throws IOException {
        mapName = in.readUTF();
        MapConfigAdapter adapter = new MapConfigAdapter();
        adapter.readData(in);
        mapConfig = adapter.getMapConfig();
    }
}
