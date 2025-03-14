// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.remote.util.StaticMetadataProvider;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteActionInputFetcher}. */
@RunWith(JUnit4.class)
public class RemoteActionInputFetcherTest extends ActionInputPrefetcherTestBase {

  private RemoteOptions options;
  private DigestUtil digestUtil;

  @Override
  public void setUp() throws IOException {
    super.setUp();
    Path dev = fs.getPath("/dev");
    dev.createDirectory();
    dev.setWritable(false);
    options = Options.getDefaults(RemoteOptions.class);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, HASH_FUNCTION);
  }

  @Override
  protected AbstractActionInputPrefetcher createPrefetcher(Map<HashCode, byte[]> cas) {
    RemoteCache remoteCache = newCache(options, digestUtil, cas);
    return new RemoteActionInputFetcher(
        new Reporter(new EventBus()),
        "none",
        "none",
        remoteCache,
        execRoot,
        tempPathGenerator,
        ImmutableList.of(),
        OutputPermissions.READONLY,
        /* useNewExitCodeForLostInputs= */ false);
  }

  @Test
  public void testStagingVirtualActionInput() throws Exception {
    // arrange
    MetadataProvider metadataProvider = new StaticMetadataProvider(new HashMap<>());
    RemoteCache remoteCache = newCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBus()),
            "none",
            "none",
            remoteCache,
            execRoot,
            tempPathGenerator,
            ImmutableList.of(),
            OutputPermissions.READONLY,
            /* useNewExitCodeForLostInputs= */ false);
    VirtualActionInput a = ActionsTestUtil.createVirtualActionInput("file1", "hello world");

    // act
    wait(actionInputFetcher.prefetchFiles(ImmutableList.of(a), metadataProvider));

    // assert
    Path p = execRoot.getRelative(a.getExecPath());
    assertThat(FileSystemUtils.readContent(p, StandardCharsets.UTF_8)).isEqualTo("hello world");
    assertThat(p.isExecutable()).isTrue();
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testStagingEmptyVirtualActionInput() throws Exception {
    // arrange
    MetadataProvider metadataProvider = new StaticMetadataProvider(new HashMap<>());
    RemoteCache remoteCache = newCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBus()),
            "none",
            "none",
            remoteCache,
            execRoot,
            tempPathGenerator,
            ImmutableList.of(),
            OutputPermissions.READONLY,
            /* useNewExitCodeForLostInputs= */ false);

    // act
    wait(
        actionInputFetcher.prefetchFiles(
            ImmutableList.of(VirtualActionInput.EMPTY_MARKER), metadataProvider));

    // assert that nothing happened
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_missingFiles_failsWithSpecificMessage() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a = createRemoteArtifact("file1", "hello world", metadata, /* cas= */ new HashMap<>());
    MetadataProvider metadataProvider = new StaticMetadataProvider(metadata);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    var error =
        assertThrows(
            ExecException.class,
            () -> wait(prefetcher.prefetchFiles(ImmutableList.of(a), metadataProvider)));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
    var m = metadataProvider.getMetadata(a);
    var digest = DigestUtil.buildDigest(m.getDigest(), m.getSize());
    assertThat(error)
        .hasMessageThat()
        .contains(String.format("%s/%s", digest.getHash(), digest.getSizeBytes()));
  }

  private RemoteCache newCache(
      RemoteOptions options, DigestUtil digestUtil, Map<HashCode, byte[]> cas) {
    Map<Digest, byte[]> cacheEntries = Maps.newHashMapWithExpectedSize(cas.size());
    for (Map.Entry<HashCode, byte[]> entry : cas.entrySet()) {
      cacheEntries.put(
          DigestUtil.buildDigest(entry.getKey().asBytes(), entry.getValue().length),
          entry.getValue());
    }
    return new RemoteCache(
        CacheCapabilities.getDefaultInstance(),
        new InMemoryCacheClient(cacheEntries),
        options,
        digestUtil);
  }
}
