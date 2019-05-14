/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.alpakka.file.DirectoryChange;
// #minimal-sample
import akka.stream.alpakka.file.javadsl.DirectoryChangesSource;
// #minimal-sample
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.StreamTestKit;
import akka.testkit.TestKit;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.jimfs.WatchServiceConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class DirectoryChangesSourceTest {

  private ActorSystem system;
  private Materializer materializer;
  private FileSystem fs;
  private Path testDir;

  @Before
  public void setup() throws Exception {
    system = ActorSystem.create();
    materializer = ActorMaterializer.create(system);

    fs =
        Jimfs.newFileSystem(
            Configuration.forCurrentPlatform()
                .toBuilder()
                .setWatchServiceConfiguration(
                    WatchServiceConfiguration.polling(10, TimeUnit.MILLISECONDS))
                .build());

    testDir = fs.getPath("testdir");

    Files.createDirectory(testDir);
  }

  @Test
  public void sourceShouldEmitOnDirectoryChanges() throws Exception {
    final TestSubscriber.Probe<Pair<Path, DirectoryChange>> probe = TestSubscriber.probe(system);

    DirectoryChangesSource.create(testDir, Duration.ofMillis(250), 200)
        .runWith(Sink.fromSubscriber(probe), materializer);

    probe.request(1);

    final Path createdFile = Files.createFile(testDir.resolve("test1file1.sample"));

    final Pair<Path, DirectoryChange> pair1 = probe.expectNext();
    assertEquals(pair1.second(), DirectoryChange.Creation);
    assertEquals(pair1.first(), createdFile);

    Files.write(createdFile, "Some data".getBytes());

    final Pair<Path, DirectoryChange> pair2 = probe.requestNext();
    assertEquals(pair2.second(), DirectoryChange.Modification);
    assertEquals(pair2.first(), createdFile);

    Files.delete(createdFile);

    final Pair<Path, DirectoryChange> pair3 = probe.requestNext();
    assertEquals(pair3.second(), DirectoryChange.Deletion);
    assertEquals(pair3.first(), createdFile);

    probe.cancel();
  }

  @Test
  public void emitMultipleChanges() throws Exception {
    final TestSubscriber.Probe<Pair<Path, DirectoryChange>> probe =
        TestSubscriber.<Pair<Path, DirectoryChange>>probe(system);

    final int numberOfChanges = 50;

    DirectoryChangesSource.create(testDir, Duration.ofMillis(250), numberOfChanges * 2)
        .runWith(Sink.fromSubscriber(probe), materializer);

    probe.request(numberOfChanges);

    final int halfRequested = numberOfChanges / 2;
    final List<Path> files = new ArrayList<>();

    for (int i = 0; i < halfRequested; i++) {
      final Path file = Files.createFile(testDir.resolve("test2files" + i));
      files.add(file);
    }

    for (int i = 0; i < halfRequested; i++) {
      probe.expectNext();
    }

    for (int i = 0; i < halfRequested; i++) {
      Files.delete(files.get(i));
    }

    for (int i = 0; i < halfRequested; i++) {
      probe.expectNext();
    }

    probe.cancel();
  }

  @After
  public void tearDown() throws Exception {
    StreamTestKit.assertAllStagesStopped(materializer);
    TestKit.shutdownActorSystem(system, FiniteDuration.apply(3, TimeUnit.SECONDS), true);
    fs.close();
  }

  public static void main(String[] args) {
    if (args.length != 1)
      throw new IllegalArgumentException("Usage: DirectoryChangesSourceTest [path]");
    final String path = args[0];

    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = ActorMaterializer.create(system);

    // #minimal-sample

    final FileSystem fs = FileSystems.getDefault();
    final Duration pollingInterval = Duration.ofSeconds(1);
    final int maxBufferSize = 1000;
    final Source<Pair<Path, DirectoryChange>, NotUsed> changes =
        DirectoryChangesSource.create(fs.getPath(path), pollingInterval, maxBufferSize);

    changes.runForeach(
        (Pair<Path, DirectoryChange> pair) -> {
          final Path changedPath = pair.first();
          final DirectoryChange change = pair.second();
          System.out.println("Path: " + changedPath + ", Change: " + change);
        },
        materializer);
    // #minimal-sample
  }
}