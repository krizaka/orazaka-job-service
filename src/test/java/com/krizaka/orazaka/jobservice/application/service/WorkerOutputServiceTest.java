package com.krizaka.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.jobservice.infrastructure.support.MediaFileStore;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkerOutputServiceTest {

  private static final String JOB = "3f2c9a10-7d1e-4b8a-9c55-0a1b2c3d4e5f";
  private static final byte[] CLIP = {0, 0, 0, 24, 'f', 't', 'y', 'p'};

  private final JobPersistenceProvider jobs = mock(JobPersistenceProvider.class);
  private final MediaFileStore store = mock(MediaFileStore.class);
  private final WorkerOutputService service = new WorkerOutputService(jobs, store, "/srv/uploads");

  private JobDto row(String owner) {
    JobDto dto = mock(JobDto.class);
    when(dto.userId()).thenReturn(owner);
    return dto;
  }

  @Test
  @DisplayName("A Studio step's job has no row: it is adopted for the run's actor, then stored")
  void adoptsTheRowOfAWorkerDrainedJob() {
    when(jobs.getJob(JOB)).thenReturn(Optional.empty());
    when(store.save("/srv/uploads", "user-1", JOB, CLIP, "video.mp4"))
        .thenReturn("/api/v1/assets/" + JOB + "/video.mp4");

    String url =
        service.store(JOB, "video.mp4", CLIP, "user-1", "orazaka.core.media.video", "STANDARD");

    assertThat(url).isEqualTo("/api/v1/assets/" + JOB + "/video.mp4");
    verify(jobs).createJob(JOB, "user-1", "orazaka.core.media.video", Map.of(), DataClass.STANDARD);
  }

  @Test
  @DisplayName("An existing row decides the owner: the relayed actor cannot redirect the file")
  void theRowsOwnerWins() {
    JobDto existing = row("owner-7");
    when(jobs.getJob(JOB)).thenReturn(Optional.of(existing));
    when(store.save(anyString(), eq("owner-7"), eq(JOB), any(), eq("video.mp4"))).thenReturn("/u");

    service.store(JOB, "video.mp4", CLIP, "someone-else", "orazaka.core.media.video", "STANDARD");

    verify(store).save("/srv/uploads", "owner-7", JOB, CLIP, "video.mp4");
    verify(jobs, never()).createJob(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("A path is never an output name")
  void refusesPaths() {
    for (String name : new String[] {"../video.mp4", "a/b.mp4", ".hidden", "", "..mp4"}) {
      assertThatThrownBy(() -> service.store(JOB, name, CLIP, "u", "f", "STANDARD"))
          .as(name)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> service.store("../x", "video.mp4", CLIP, "u", "f", "STANDARD"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Adopting a row needs a declared data class — never defaulted (ADR-065)")
  void aDataClassIsDeclared() {
    when(jobs.getJob(JOB)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.store(JOB, "video.mp4", CLIP, "u", "f", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never defaulted");
    assertThatThrownBy(() -> service.store(JOB, "video.mp4", CLIP, "", "f", "STANDARD"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(jobs, never()).createJob(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("An empty output is refused; a failed write is ours, not the worker's")
  void emptyAndUnwritable() {
    assertThatThrownBy(() -> service.store(JOB, "video.mp4", new byte[0], "u", "f", "STANDARD"))
        .isInstanceOf(IllegalArgumentException.class);

    JobDto existing = row("owner-7");
    when(jobs.getJob(JOB)).thenReturn(Optional.of(existing));
    when(store.save(any(), any(), any(), any(), any())).thenReturn("");
    assertThatThrownBy(() -> service.store(JOB, "video.mp4", CLIP, "u", "f", "STANDARD"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("An actor reads back the output of a job it owns")
  void loadsTheOwnersOutput() {
    JobDto owned = row("owner-7");
    when(jobs.getJob(JOB)).thenReturn(Optional.of(owned));
    when(store.read("/srv/uploads", "owner-7", JOB, "video.mp4")).thenReturn(Optional.of(CLIP));

    assertThat(service.load(JOB, "video.mp4", "owner-7")).contains(CLIP);
  }

  @Test
  @DisplayName("Another actor's output is not found: same answer as one that does not exist")
  void anotherActorsOutputIsNotFound() {
    JobDto owned = row("owner-7");
    when(jobs.getJob(JOB)).thenReturn(Optional.of(owned));

    assertThat(service.load(JOB, "video.mp4", "intruder")).isEmpty();
    assertThat(service.load(JOB, "video.mp4", "")).isEmpty();
    assertThat(service.load(JOB, "video.mp4", null)).isEmpty();
    verify(store, never()).read(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("A job without a row has nothing to read; a path is never an output name")
  void unknownJobAndPaths() {
    when(jobs.getJob(JOB)).thenReturn(Optional.empty());

    assertThat(service.load(JOB, "video.mp4", "owner-7")).isEmpty();
    assertThatThrownBy(() -> service.load(JOB, "../video.mp4", "owner-7"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.load("../x", "video.mp4", "owner-7"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
