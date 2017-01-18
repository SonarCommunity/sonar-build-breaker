/*
 * SonarQube Build Breaker Plugin
 * Copyright (C) 2009-2016 Matthew DeTullio and contributors
 * mailto:sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.buildbreaker;

import com.google.common.collect.Lists;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sonar.api.batch.AnalysisMode;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.Project;
import org.sonarqube.ws.WsCe.Task;
import org.sonarqube.ws.WsCe.TaskResponse;
import org.sonarqube.ws.WsCe.TaskStatus;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.Comparator;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.Condition;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.ProjectStatus;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.Status;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsConnector;
import org.sonarqube.ws.client.WsRequest;
import org.sonarqube.ws.client.WsResponse;
import org.sonarqube.ws.client.qualitygate.ProjectStatusWsRequest;
import org.sonarqube.ws.client.qualitygate.QualityGatesService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.hamcrest.CoreMatchers.isA;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest(TaskResponse.class)
public class QualityGateBreakerTest {
  private static final String TEST_TASK_ID = "Abc123";
  private static final String TEST_ANALYSIS_ID = "Def456";

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testDisabledFromWrongAnalysisMode() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(false);

    FileSystem fileSystem = mock(FileSystem.class);
    Settings settings = new Settings();

    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    // No exception

    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }

  @Test
  public void testDisabledFromSkipSetting() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(true);

    FileSystem fileSystem = mock(FileSystem.class);
    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.SKIP_KEY, true);

    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    // No exception

    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }

  @Test
  public void testNoReportTaskTxtFile() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(true);

    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.SKIP_KEY, false);

    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    thrown.expect(IllegalStateException.class);
    thrown.expectCause(isA(IOException.class));
    thrown.expectMessage("Unable to load properties from file");

    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }

  /**
   * Mock everything up until a query would be attempted.  Because max attempts is unset, it defaults to 0.  Expect
   * immediate failure.
   */
  @Test
  public void testQueryMaxAttemptsReached() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(true);

    FileSystem fileSystem = mock(FileSystem.class);
    when(fileSystem.workDir()).thenReturn(new File("src/test/resources/org/sonar/plugins/buildbreaker"));

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.SKIP_KEY, false);

    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Report processing is taking longer than the configured wait limit.");

    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }

  @Test
  public void testSingleQueryInProgressStatus() throws IOException {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    WsConnector wsConnector = mock(WsConnector.class);
    WsResponse wsResponse = mock(WsResponse.class);
    // yuck
    PowerMockito.mockStatic(TaskResponse.class);
    TaskResponse taskResponse = mock(TaskResponse.class);
    Task task = Task.newBuilder().setStatus(TaskStatus.IN_PROGRESS).build();

    when(wsClient.wsConnector()).thenReturn(wsConnector);
    when(wsConnector.call(any(WsRequest.class))).thenReturn(wsResponse);
    when(TaskResponse.parseFrom(any(InputStream.class))).thenReturn(taskResponse);
    when(taskResponse.getTask()).thenReturn(task);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Report processing is taking longer than the configured wait limit.");

    new QualityGateBreaker(analysisMode, fileSystem, settings).getAnalysisId(wsClient, TEST_TASK_ID);
  }

  @Test
  public void testSingleQueryPendingStatus() throws IOException {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    WsConnector wsConnector = mock(WsConnector.class);
    WsResponse wsResponse = mock(WsResponse.class);
    // yuck
    PowerMockito.mockStatic(TaskResponse.class);
    TaskResponse taskResponse = mock(TaskResponse.class);
    Task task = Task.newBuilder().setStatus(TaskStatus.PENDING).build();

    when(wsClient.wsConnector()).thenReturn(wsConnector);
    when(wsConnector.call(any(WsRequest.class))).thenReturn(wsResponse);
    when(TaskResponse.parseFrom(any(InputStream.class))).thenReturn(taskResponse);
    when(taskResponse.getTask()).thenReturn(task);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Report processing is taking longer than the configured wait limit.");

    new QualityGateBreaker(analysisMode, fileSystem, settings).getAnalysisId(wsClient, TEST_TASK_ID);
  }

  @Test
  public void testSingleQueryFailedStatus() throws IOException {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    WsConnector wsConnector = mock(WsConnector.class);
    WsResponse wsResponse = mock(WsResponse.class);
    // yuck
    PowerMockito.mockStatic(TaskResponse.class);
    TaskResponse taskResponse = mock(TaskResponse.class);
    Task task = Task.newBuilder().setStatus(TaskStatus.FAILED).build();

    when(wsClient.wsConnector()).thenReturn(wsConnector);
    when(wsConnector.call(any(WsRequest.class))).thenReturn(wsResponse);
    when(TaskResponse.parseFrom(any(InputStream.class))).thenReturn(taskResponse);
    when(taskResponse.getTask()).thenReturn(task);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Report processing did not complete successfully: FAILED");

    new QualityGateBreaker(analysisMode, fileSystem, settings).getAnalysisId(wsClient, TEST_TASK_ID);
  }

  @Test
  public void testSingleQueryCanceledStatus() throws IOException {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    WsConnector wsConnector = mock(WsConnector.class);
    WsResponse wsResponse = mock(WsResponse.class);
    // yuck
    PowerMockito.mockStatic(TaskResponse.class);
    TaskResponse taskResponse = mock(TaskResponse.class);
    Task task = Task.newBuilder().setStatus(TaskStatus.CANCELED).build();

    when(wsClient.wsConnector()).thenReturn(wsConnector);
    when(wsConnector.call(any(WsRequest.class))).thenReturn(wsResponse);
    when(TaskResponse.parseFrom(any(InputStream.class))).thenReturn(taskResponse);
    when(taskResponse.getTask()).thenReturn(task);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Report processing did not complete successfully: CANCELED");

    new QualityGateBreaker(analysisMode, fileSystem, settings).getAnalysisId(wsClient, TEST_TASK_ID);
  }

  @Test
  public void testSingleQuerySuccessStatus() throws IOException {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    WsConnector wsConnector = mock(WsConnector.class);
    WsResponse wsResponse = mock(WsResponse.class);
    // yuck
    PowerMockito.mockStatic(TaskResponse.class);
    TaskResponse taskResponse = mock(TaskResponse.class);
    Task task = Task.newBuilder().setStatus(TaskStatus.SUCCESS).setAnalysisId(TEST_ANALYSIS_ID).build();

    when(wsClient.wsConnector()).thenReturn(wsConnector);
    when(wsConnector.call(any(WsRequest.class))).thenReturn(wsResponse);
    when(TaskResponse.parseFrom(any(InputStream.class))).thenReturn(taskResponse);
    when(taskResponse.getTask()).thenReturn(task);

    String analysisId = new QualityGateBreaker(analysisMode, fileSystem, settings).getAnalysisId(wsClient, TEST_TASK_ID);
    assertEquals(TEST_ANALYSIS_ID, analysisId);
  }

  @Test
  public void testSingleQueryIOException() throws IOException {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    WsConnector wsConnector = mock(WsConnector.class);
    WsResponse wsResponse = mock(WsResponse.class);
    // yuck
    PowerMockito.mockStatic(TaskResponse.class);

    when(wsClient.wsConnector()).thenReturn(wsConnector);
    when(wsConnector.call(any(WsRequest.class))).thenReturn(wsResponse);
    when(TaskResponse.parseFrom(any(InputStream.class))).thenThrow(new IOException());

    thrown.expect(IllegalStateException.class);
    thrown.expectCause(isA(IOException.class));

    new QualityGateBreaker(analysisMode, fileSystem, settings).getAnalysisId(wsClient, TEST_TASK_ID);
  }

  @Test
  public void testQualityGateStatusWarning() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    QualityGatesService qualityGatesService = mock(QualityGatesService.class);
    ProjectStatus projectStatus = ProjectStatus.newBuilder().setStatus(Status.WARN).build();
    ProjectStatusWsResponse projectStatusWsResponse = ProjectStatusWsResponse.newBuilder().setProjectStatus(projectStatus).build();

    when(wsClient.qualityGates()).thenReturn(qualityGatesService);
    when(qualityGatesService.projectStatus(any(ProjectStatusWsRequest.class))).thenReturn(projectStatusWsResponse);

    // No exception

    new QualityGateBreaker(analysisMode, fileSystem, settings).checkQualityGate(wsClient, TEST_ANALYSIS_ID);
  }

  @Test
  public void testQualityGateStatusError() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    QualityGatesService qualityGatesService = mock(QualityGatesService.class);
    ProjectStatus projectStatus = ProjectStatus.newBuilder().setStatus(Status.ERROR).build();
    ProjectStatusWsResponse projectStatusWsResponse = ProjectStatusWsResponse.newBuilder().setProjectStatus(projectStatus).build();

    when(wsClient.qualityGates()).thenReturn(qualityGatesService);
    when(qualityGatesService.projectStatus(any(ProjectStatusWsRequest.class))).thenReturn(projectStatusWsResponse);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Project does not pass the quality gate.");

    new QualityGateBreaker(analysisMode, fileSystem, settings).checkQualityGate(wsClient, TEST_ANALYSIS_ID);
  }

  @Test
  public void testQualityGateStatusOk() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    FileSystem fileSystem = mock(FileSystem.class);

    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY, 1);

    WsClient wsClient = mock(WsClient.class);
    QualityGatesService qualityGatesService = mock(QualityGatesService.class);
    ProjectStatus projectStatus = ProjectStatus.newBuilder().setStatus(Status.OK).build();
    ProjectStatusWsResponse projectStatusWsResponse = ProjectStatusWsResponse.newBuilder().setProjectStatus(projectStatus).build();

    when(wsClient.qualityGates()).thenReturn(qualityGatesService);
    when(qualityGatesService.projectStatus(any(ProjectStatusWsRequest.class))).thenReturn(projectStatusWsResponse);

    // No exception

    new QualityGateBreaker(analysisMode, fileSystem, settings).checkQualityGate(wsClient, TEST_ANALYSIS_ID);
  }

  @Test
  public void testLogConditions() {
    List<Condition> conditions = Lists.newArrayList();
    conditions.add(Condition.newBuilder().setStatus(Status.WARN).setMetricKey("violations").setActualValue("20").setComparator(Comparator.GT).setWarningThreshold("10").build());
    conditions.add(Condition.newBuilder().setStatus(Status.WARN).setMetricKey("uncovered_lines").setActualValue("20").setComparator(Comparator.NE).setWarningThreshold("0").build());
    conditions.add(Condition.newBuilder().setStatus(Status.ERROR).setMetricKey("comment_lines").setActualValue("0").setComparator(Comparator.EQ).setErrorThreshold("0").build());
    conditions.add(Condition.newBuilder().setStatus(Status.ERROR).setMetricKey("custom_metric").setActualValue("0").setComparator(Comparator.LT).setErrorThreshold("10").build());
    conditions.add(Condition.newBuilder().setStatus(Status.OK).setMetricKey("blocker_violations").setActualValue("0").setComparator(Comparator.LT).setErrorThreshold("1").build());

    int errors = QualityGateBreaker.logConditions(conditions);
    assertEquals(2, errors);
  }
  
  @Test
  public void testDisableForPreviewModeWithDefaultSettings() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(false);

    FileSystem fileSystem = mock(FileSystem.class);
    Settings settings = new Settings();
    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    // No exception

    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }
  
  @Test
  public void testDisableForPreviewModeWithFlagFalseAnalysisMode() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(false);
    when(analysisMode.isPreview()).thenReturn(true);

    FileSystem fileSystem = mock(FileSystem.class);
    when(fileSystem.workDir()).thenReturn(new File("src/test/resources/org/sonar/plugins/buildbreaker"));
    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.SKIP_FOR_PREVIEW, false);
    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Report processing is taking longer than the configured wait limit.");


    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }
  
  @Test
  public void testEnableForPreviewModeWithFlagTrueAnalysisMode() {
    AnalysisMode analysisMode = mock(AnalysisMode.class);
    when(analysisMode.isPublish()).thenReturn(false);
    when(analysisMode.isPreview()).thenReturn(true);

    FileSystem fileSystem = mock(FileSystem.class);
    Settings settings = new Settings();
    settings.setProperty(BuildBreakerPlugin.SKIP_FOR_PREVIEW, true);
    Project project = mock(Project.class);
    SensorContext sensorContext = mock(SensorContext.class);

    // No exception

    new QualityGateBreaker(analysisMode, fileSystem, settings).executeOn(project, sensorContext);
  }
}
