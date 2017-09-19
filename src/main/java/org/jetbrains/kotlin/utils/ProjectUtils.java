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
package org.jetbrains.kotlin.utils;

import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.swing.text.Document;
import javax.swing.text.StyledDocument;
import org.jetbrains.kotlin.builder.KotlinPsiManager;
import org.jetbrains.kotlin.bundledcompiler.BundledCompiler;
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper;
import org.jetbrains.kotlin.projectsextensions.ClassPathExtender;
import org.jetbrains.kotlin.projectsextensions.maven.MavenHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.log.KotlinLogger;
import org.jetbrains.kotlin.psi.KtFile;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.modules.Places;
import org.openide.util.Exceptions;

public class ProjectUtils {

    private static final String LIB_FOLDER = "lib";
    private static final String LIB_EXTENSION = "jar";
    public static final String FILE_SEPARATOR = System.getProperty("file.separator");
    public static String KT_HOME;

    static {
        if (System.getenv("KOTLIN_HOME") != null) {
            KT_HOME = System.getenv("KOTLIN_HOME") + FILE_SEPARATOR;
        } else if (System.getenv("KT_HOME") != null) {
            KT_HOME = System.getenv("KT_HOME") + FILE_SEPARATOR;
        }
    }

    public static void checkKtHome() throws IOException {
        if (KT_HOME == null){
            File userDirectory = Places.getUserDirectory();
            if (userDirectory == null) {
                KotlinLogger.INSTANCE.logWarning("KT_HOME is null!");
                return;
            }
            
            FileObject dir = FileUtil.toFileObject(userDirectory);
            if (dir == null || dir.getFileObject("kotlinc") == null){
                KotlinLogger.INSTANCE.logInfo("No kotlinc folder. Adding bundled compiler.");
                BundledCompiler.getKotlinc();
            } else {
                FileObject kotlincDir = dir.getFileObject("kotlinc");
                FileObject build = kotlincDir.getFileObject("build.txt");
                if (build == null || !build.asText("UTF-8").equals(BundledCompiler.KOTLIN_VERSION)) {
                    BundledCompiler.getKotlinc();
                    KotlinLogger.INSTANCE.logInfo("New version of bundled compiler");
                }
            }
            KT_HOME = userDirectory.getAbsolutePath() + FILE_SEPARATOR + "kotlinc"
                    + FILE_SEPARATOR;
        }
    }

    private static Set<String> createListOfClassPaths(ClassPath boot, ClassPath src, ClassPath compile) {
        Set<String> classpath = Sets.newHashSet();
        
        for (ClassPath.Entry entry : boot.entries()){
            String path = entry.getURL().getFile();
            if (path != null){
                try {
                    classpath.add(URLDecoder.decode(path, "UTF-8"));
                } catch (UnsupportedEncodingException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        }
        
        for (ClassPath.Entry entry : src.entries()){
            String path = entry.getURL().getPath();
            if (path != null){
                try {
                    classpath.add(URLDecoder.decode(path, "UTF-8"));
                } catch (UnsupportedEncodingException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        }
        
        for (ClassPath.Entry entry : compile.entries()){
            String path = entry.getURL().getPath();
            
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            if (fo != null && fo.isFolder()) continue;
            
            if (path != null){
                try {
                    classpath.add(URLDecoder.decode(path, "UTF-8"));
                } catch (UnsupportedEncodingException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
        }
        
        return classpath;
    }
    
    private static Set<String> getProjectClassPath(Project project) {
        ClassPathExtender extendedProvider = KotlinProjectHelper.INSTANCE.getExtendedClassPath(project);
        ClassPath boot = extendedProvider.getProjectSourcesClassPath(ClassPath.BOOT);
        ClassPath src = extendedProvider.getProjectSourcesClassPath(ClassPath.SOURCE);
        ClassPath compile = extendedProvider.getProjectSourcesClassPath(ClassPath.COMPILE);
        
        return createListOfClassPaths(boot, src, compile);
    }
    
    @NotNull
    public static Set<String> getClasspath(Project project) {
        if (KotlinProjectHelper.INSTANCE.checkProject(project)) {
            return getProjectClassPath(project);
        }
        
        return Collections.emptySet();
    }

    public static String buildLibPath(String libName) {
        return KT_HOME + buildLibName(libName);
    }

    private static String buildLibName(String libName) {
        return LIB_FOLDER + FILE_SEPARATOR + libName + "." + LIB_EXTENSION;
    }
    
    public static KtFile getKtFile(FileObject file){
        return KotlinPsiManager.INSTANCE.getParsedFile(file);
    }
    
    public static KtFile getKtFile(String code, FileObject file){
        return KotlinPsiManager.INSTANCE.parseTextForDiagnostic(code, file);
    }
    
    @NotNull
    public static List<KtFile> getSourceFiles(@NotNull Project project){
        List<KtFile> ktFiles = new ArrayList<>();
        
        for (FileObject file : KotlinPsiManager.INSTANCE.getFilesByProject(project, true)){
            ktFiles.add(getKtFile(file));
            
        }
        
        return ktFiles; 
    }
    
    @NotNull
    public static List<KtFile> getSourceFilesWithDependencies(@NotNull Project project){
        List<KtFile> depFiles = new ArrayList<>();
        if (project.getClass().getName().
                    equals("org.netbeans.modules.maven.NbMavenProjectImpl")) {
            List<? extends Project> depProjects = MavenHelper.getDependencyProjects(project);
            for (Project depProject : depProjects) {
                for (FileObject file : KotlinPsiManager.INSTANCE.getFilesByProject(depProject, false)){
                    depFiles.add(getKtFile(file));
                }
            }
        }
        List<KtFile> files = getSourceFiles(project);
        files.addAll(depFiles);
        
        return files;
    }

    public static Project getKotlinProjectForFileObject(FileObject file) {
        return FileOwnerQuery.getOwner(file);
    }
    
    public static FileObject getFileObjectForDocument(Document doc) {
        if (doc == null) {
            return null;
        }
        Object sdp = doc.getProperty(Document.StreamDescriptionProperty);

        if (sdp instanceof FileObject) {
            return (FileObject) sdp;
        }

        if (sdp instanceof DataObject) {
            DataObject dobj = (DataObject) sdp;
            return dobj.getPrimaryFile();
        }

        return null;
    }
    
    public static DataObject getDataObjectFromFileObject(FileObject file) {
        DataObject dataObject = null;
        try {
            dataObject = DataObject.find(file);
        } catch (DataObjectNotFoundException ex) {
            Exceptions.printStackTrace(ex);
        }
        
        return dataObject;
    }
    
    public static StyledDocument getDocumentFromFileObject(FileObject file) throws IOException{
        DataObject dataObject = null;
        try {
            dataObject = DataObject.find(file);
        } catch (DataObjectNotFoundException ex) {
            Exceptions.printStackTrace(ex);
        }
        if (dataObject == null){
            return null;
        }
        
        EditorCookie editorCookie = (EditorCookie) dataObject.getLookup().lookup(EditorCookie.class);
        if (editorCookie == null){
            return null;
        }
        
        editorCookie.open();
        return editorCookie.openDocument();
    }
    
    public static Project getValidProject() {
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            if (KotlinProjectHelper.INSTANCE.checkProject(project)) {
                return project;
            }
        }
        
        return null;
    }
    
}
