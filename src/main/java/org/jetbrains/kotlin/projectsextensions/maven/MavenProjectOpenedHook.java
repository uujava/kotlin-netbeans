/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 *
 *******************************************************************************/
package org.jetbrains.kotlin.projectsextensions.maven;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import org.jetbrains.kotlin.log.KotlinLogger;
import org.jetbrains.kotlin.model.KotlinEnvironment;
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.progress.ProgressHandleFactory;
import org.netbeans.api.project.Project;
import org.netbeans.modules.maven.api.NbMavenProject;
import org.netbeans.spi.project.ui.ProjectOpenedHook;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileRenameEvent;

/**
 *
 * @author Alexander.Baratynski
 */
public class MavenProjectOpenedHook extends ProjectOpenedHook{

    private static volatile boolean progressHandleRun = false;
    private final Project project;
    
    public MavenProjectOpenedHook(Project project) {
        this.project = project;
    }
    
    @Override
    protected void projectOpened() {
        Thread thread = new Thread(){
                @Override
                public void run(){
                        Runnable run = new Runnable(){
                            @Override
                            public void run(){
                                progressHandleRun = true;
                                final ProgressHandle progressBar = 
                                    ProgressHandleFactory.createHandle("Loading Kotlin environment");
                                progressBar.start();
                                KotlinEnvironment.Companion.getEnvironment(project);
                                progressBar.finish();
                                progressHandleRun = false;
                            }
                        };
                        if (!progressHandleRun) {
                            KotlinProjectHelper.INSTANCE.postTask(run);
                        }
                        
                        PomXmlChangeListener pomListener = new PomXmlChangeListener(project);
                        final FileObject pomXml = project.getProjectDirectory().getFileObject("pom.xml");
                        
                        if (pomXml != null) {
                            pomXml.addFileChangeListener(pomListener);
                            NbMavenProject mavenProject = getProjectWatcher();
                            mavenProject.addPropertyChangeListener(pomListener);
                        }
                        
                        KotlinProjectHelper.INSTANCE.doInitialScan(project);
                }
            };
        thread.start();
    }
    private NbMavenProject getProjectWatcher() {
        Class clazz = project.getClass();
        try {
            Method getProjectWatcher = clazz.getMethod("getProjectWatcher");
            return (NbMavenProject) getProjectWatcher.invoke(project);
        } catch (ReflectiveOperationException ex) {} 
        
        return null;
    }
    @Override
    protected void projectClosed() {
        KotlinProjectHelper.INSTANCE.removeProjectCache(project);
    }
    
    private static class PomXmlChangeListener implements PropertyChangeListener, FileChangeListener {

        private final Project project;
        private boolean changed = false;
        
        public PomXmlChangeListener(Project project) {
            this.project = project;
        }
        
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (changed) {
                KotlinLogger.INSTANCE.logInfo("CHANGED");
                KotlinProjectHelper.INSTANCE.updateExtendedClassPath(project);
                changed = false;
            }
        }

        @Override
        public void fileFolderCreated(FileEvent fe) {}

        @Override
        public void fileDataCreated(FileEvent fe) {}

        @Override
        public void fileChanged(FileEvent fe) {
            changed = true;
        }

        @Override
        public void fileDeleted(FileEvent fe) {}

        @Override
        public void fileRenamed(FileRenameEvent fre) {}

        @Override
        public void fileAttributeChanged(FileAttributeEvent fae) {}
        
    }
    
}