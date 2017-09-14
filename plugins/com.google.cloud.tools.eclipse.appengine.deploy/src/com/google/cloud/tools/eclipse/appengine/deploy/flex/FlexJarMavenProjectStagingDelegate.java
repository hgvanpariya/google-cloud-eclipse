/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.tools.eclipse.appengine.deploy.flex;

import com.google.cloud.tools.appengine.api.AppEngineException;
import com.google.cloud.tools.eclipse.appengine.deploy.CloudSdkStagingHelper;
import com.google.cloud.tools.eclipse.appengine.deploy.Messages;
import com.google.cloud.tools.eclipse.util.status.StatusUtil;
import com.google.common.annotations.VisibleForTesting;
import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.RefreshUtil;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.m2e.actions.MavenLaunchConstants;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import org.eclipse.ui.console.MessageConsoleStream;

public class FlexJarMavenProjectStagingDelegate extends FlexStagingDelegate {

  private final IPath appEngineDirectory;

  public FlexJarMavenProjectStagingDelegate(IPath appEngineDirectory) {
    super(appEngineDirectory);
    this.appEngineDirectory = appEngineDirectory;
  }

  @VisibleForTesting
  static IPath getJreContainerPath(IProject project) throws CoreException {
    IJavaProject javaProject = JavaCore.create(project);
    for (IClasspathEntry entry : javaProject.getRawClasspath()) {
      if (JavaRuntime.JRE_CONTAINER.equals(entry.getPath().segment(0))) {
        return entry.getPath();
      }
    }
    throw new CoreException(StatusUtil.error(FlexJarMavenProjectStagingDelegate.class,
        "Project " + project.getName() + " does not have JRE configured."));
  }

  /**
   * Waits until {@code launch} terminates or {@code monitor} is canceled. if the monitor is
   * canceled, attempts to terminate the launch before returning.
   *
   * @return true if the launch terminated normally; false otherwise
   */
  @VisibleForTesting
  static boolean waitUntilLaunchTerminates(ILaunch launch, IProgressMonitor monitor)
      throws InterruptedException, DebugException {
    while (!launch.isTerminated() && !monitor.isCanceled()) {
      Thread.sleep(100 /*ms*/);
    }

    if (monitor.isCanceled()) {
      launch.terminate();
      return false;
    }
    for (IProcess process : launch.getProcesses()) {
      if (process.getExitValue() != 0) {
        return false;
      }
    }
    return true;
  }

  private static IPath getFinalJarPath(IProject project) throws CoreException {
    IMavenProjectRegistry projectManager = MavenPlugin.getMavenProjectRegistry();
    IMavenProjectFacade projectFacade = projectManager.create(project, new NullProgressMonitor());
    MavenProject mavenProject = projectFacade.getMavenProject(new NullProgressMonitor());

    String buildDirectory = mavenProject.getBuild().getDirectory();
    String finalName = mavenProject.getBuild().getFinalName();
    String finalJarPath = buildDirectory + "/" + finalName + "." + mavenProject.getPackaging();
    return new Path(finalJarPath);
  }

  @VisibleForTesting
  static ILaunchConfiguration createMavenPackagingLaunchConfiguration(IProject project)
      throws CoreException {
    ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
    ILaunchConfigurationType launchConfigurationType = launchManager
        .getLaunchConfigurationType(MavenLaunchConstants.LAUNCH_CONFIGURATION_TYPE_ID);

    String launchConfigName = "CT4E App Engine flexible deploy JAR packaging "
        + project.getLocation().toString().replaceAll("[^a-zA-Z0-9]", "_");

    ILaunchConfigurationWorkingCopy workingCopy = launchConfigurationType.newInstance(
        null /*container*/, launchConfigName);
    workingCopy.setAttribute(ILaunchManager.ATTR_PRIVATE, true);
    // IDebugUIConstants.ATTR_LAUNCH_IN_BACKGROUND;
    workingCopy.setAttribute("org.eclipse.debug.ui.ATTR_LAUNCH_IN_BACKGROUND", true);
    workingCopy.setAttribute(MavenLaunchConstants.ATTR_POM_DIR, project.getLocation().toString());
    workingCopy.setAttribute(MavenLaunchConstants.ATTR_GOALS, "package");
    workingCopy.setAttribute(RefreshUtil.ATTR_REFRESH_SCOPE, "${project}");
    workingCopy.setAttribute(RefreshUtil.ATTR_REFRESH_RECURSIVE, true);

    IPath jreContainerPath = getJreContainerPath(project);
    workingCopy.setAttribute(
        IJavaLaunchConfigurationConstants.ATTR_JRE_CONTAINER_PATH, jreContainerPath.toString());

    return workingCopy;
  }

  @Override
  public IStatus stage(IProject project, IPath stagingDirectory, IPath safeWorkDirectory,
      MessageConsoleStream stdoutOutputStream, MessageConsoleStream stderrOutputStream,
      IProgressMonitor monitor) {
    SubMonitor subMonitor = SubMonitor.convert(monitor, 200);

    boolean result = stagingDirectory.toFile().mkdirs();
    if (!result) {
      return StatusUtil.error(this, "Could not create staging directory " + stagingDirectory);
    }

    try {
      ILaunchConfiguration config = createMavenPackagingLaunchConfiguration(project);
      ILaunch launch = config.launch("run", subMonitor.newChild(100));
      if (!waitUntilLaunchTerminates(launch, monitor)) {
        return Status.CANCEL_STATUS;
      }

      IPath jar = getFinalJarPath(project);
      CloudSdkStagingHelper.stageFlexible(appEngineDirectory, jar, stagingDirectory,
          subMonitor.newChild(60));
      return Status.OK_STATUS;
    } catch (AppEngineException ex) {
      return StatusUtil.error(this, Messages.getString("deploy.job.staging.failed"), ex);
    } catch (CoreException ex) {
      return StatusUtil.error(this, "jar publishing failed", ex);
    } catch (InterruptedException ex) {
      return Status.CANCEL_STATUS;
    } finally {
      subMonitor.done();
    }
  }

}