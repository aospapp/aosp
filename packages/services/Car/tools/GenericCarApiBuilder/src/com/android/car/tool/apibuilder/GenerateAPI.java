/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.tool.apibuilder;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to generate API txt file.
 */
public final class GenerateAPI {

    private static final boolean DBG = false;
    private static final String ANDROID_BUILD_TOP = "ANDROID_BUILD_TOP";
    private static final String CAR_API_PATH = "/packages/services/Car/car-lib/src/android/car";
    private static final String API_TXT_SAVE_PATH =
            "/packages/services/Car/tools/GenericCarApiBuilder/";
    private static final String COMPLETE_API_LIST = "complete_api_list.txt";
    private static final String UN_ANNOTATED_API_LIST = "un_annotated_api_list.txt";
    private static final String TAB = "    ";

    /**
     * Main method for generate API txt file.
     */
    public static void main(final String[] args) throws Exception {
        try {
            String rootDir = System.getenv(ANDROID_BUILD_TOP);
            String carLibPath = rootDir + CAR_API_PATH;
            List<File> allJavaFiles = getAllFiles(new File(carLibPath));

            if  (args.length > 0 &&  args[0].equalsIgnoreCase("--classes-only")) {
                for (int i = 0; i < allJavaFiles.size(); i++) {
                    printAllClasses(allJavaFiles.get(i));
                }
                return;
            }

            List<String> allAPIs = new ArrayList<>();
            for (int i = 0; i < allJavaFiles.size(); i++) {
                allAPIs.addAll(parseJavaFile(allJavaFiles.get(i), /* onlyUnannotated= */ false));
            }

            // write all APIs by default.
            String toolPath = rootDir + API_TXT_SAVE_PATH;
            Path file = Paths.get(toolPath + COMPLETE_API_LIST);
            Files.write(file, allAPIs, StandardCharsets.UTF_8);

            // create only un-annotated file for manual work
            allAPIs = new ArrayList<>();
            for (int i = 0; i < allJavaFiles.size(); i++) {
                allAPIs.addAll(parseJavaFile(allJavaFiles.get(i), /* onlyUnannotated= */ true));
            }

            // write all un-annotated APIs.
            if (allAPIs.size() > 0) {
                if (args.length > 0 && args[0].equalsIgnoreCase("--write-un-annotated-to-file")) {
                    file = Paths.get(toolPath + UN_ANNOTATED_API_LIST);
                    Files.write(file, allAPIs, StandardCharsets.UTF_8);
                } else {
                    System.out.println("*********Un-annotated APIs************");
                    for (int i = 0; i < allAPIs.size(); i++) {
                        System.out.println(allAPIs.get(i));
                    }
                }
            }
        } catch (Exception e) {
            throw e;
        }
    }

    private static List<File> getAllFiles(File folderName) {
        List<File> allFiles = new ArrayList<>();
        File[] files = folderName.listFiles();

        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && files[i].getName().endsWith(".java")) {
                if (DBG) {
                    System.out.printf("File added %s\n", files[i]);
                }
                allFiles.add(files[i]);
            }
            if (files[i].isDirectory()) {
                allFiles.addAll(getAllFiles(files[i]));
            }
        }
        return allFiles;
    }

    private static void printAllClasses(File file) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String packageName = cu.getPackageDeclaration().get().getNameAsString();

        new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, Object arg) {
                if (classOrInterfaceDeclaration.isPrivate()) {
                    return;
                }

                String className = classOrInterfaceDeclaration.getFullyQualifiedName().get()
                        .substring(packageName.length() + 1);
                System.out.println("\"" + packageName + "." + className.replace(".", "$") + "\",");
            }
        }.visit(cu, null);
    }

    private static List<String> parseJavaFile(File file, boolean onlyUnannotated) throws Exception {
        List<String> parsedList = new ArrayList<>();

        // Add code to parse file
        CompilationUnit cu = StaticJavaParser.parse(file);
        String packageName = cu.getPackageDeclaration().get().getNameAsString();

        new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Object arg) {
                if (n.isPrivate()) {
                    return;
                }

                String className =
                        n.getFullyQualifiedName().get().substring(packageName.length() + 1);
                String classType = n.isInterface() ? "interface" : "class";
                boolean hiddenClass = false;

                if (!n.getJavadoc().isEmpty()) {
                    hiddenClass = n.getJavadoc().get().toText().contains("@hide");
                }

                boolean isClassSystemAPI = false;

                NodeList<AnnotationExpr> classAnnotations = n.getAnnotations();
                for (int j = 0; j < classAnnotations.size(); j++) {
                    if (classAnnotations.get(j).getName().asString().contains("SystemApi")) {
                        isClassSystemAPI = true;
                    }
                }

                String classDeclaration = classType + " " + (hiddenClass ? "@hide " : "")
                        + (isClassSystemAPI ? "@SystemApi " : "") + className + " package "
                        + packageName;

                parsedList.add(classDeclaration);
                if (DBG) {
                    System.out.println(classDeclaration);
                }

                int originalSizeOfParseList = parsedList.size();

                List<FieldDeclaration> fields = n.getFields();
                for (int i = 0; i < fields.size(); i++) {
                    FieldDeclaration field = fields.get(i);
                    if (field.isPrivate()) {
                        continue;
                    }

                    String fieldName = field.getVariables().get(0).getName().asString();
                    String fieldType = field.getVariables().get(0).getTypeAsString();
                    boolean fieldInitialized =
                            !field.getVariables().get(0).getInitializer().isEmpty();
                    String fieldInitializedValue = "";
                    if (fieldInitialized) {
                        fieldInitializedValue =
                                field.getVariables().get(0).getInitializer().get().toString();
                    }

                    // special case
                    if (fieldName.equalsIgnoreCase("CREATOR")) {
                        fieldInitialized = false;
                    }

                    boolean isSystem = false;
                    boolean isAddedIn = false;
                    boolean isAddedInOrBefore = false;
                    boolean isHidden = false;

                    if (!field.getJavadoc().isEmpty()) {
                        isHidden = field.getJavadoc().get().toText().contains("@hide");
                    }

                    NodeList<AnnotationExpr> annotations = field.getAnnotations();
                    for (int j = 0; j < annotations.size(); j++) {
                        String annotationString = annotations.get(j).getName().asString();
                        if (annotationString.contains("SystemApi")) {
                            isSystem = true;
                        }
                        if (annotationString.contains("AddedInOrBefore")) {
                            isAddedInOrBefore = true;
                        }
                        if (annotationString.equals("AddedIn")) {
                            isAddedIn = true;
                        }
                    }

                    if ((isAddedInOrBefore || isAddedIn) && onlyUnannotated) {
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("field ");
                    if (isHidden) {
                        sb.append("@hide ");
                    }

                    if (isSystem) {
                        sb.append("@SystemApi ");
                    }

                    if (isAddedInOrBefore) {
                        sb.append("@AddedInOrBefore ");
                    }

                    if (isAddedIn) {
                        sb.append("@AddedIn ");
                    }

                    sb.append(fieldType);
                    sb.append(" ");
                    sb.append(fieldName);

                    if (fieldInitialized) {
                        sb.append(" = ");
                        sb.append(fieldInitializedValue);
                    }
                    sb.append(";");


                    if (DBG) {
                        System.out.printf("%s%s\n", TAB, sb);
                    }
                    parsedList.add(TAB + sb);
                }

                // get all the methods
                List<MethodDeclaration> methods = n.getMethods();
                for (int i = 0; i < methods.size(); i++) {
                    MethodDeclaration method = methods.get(i);
                    if (method.isPrivate()) {
                        continue;
                    }
                    String returnType = method.getTypeAsString();
                    String methodName = method.getName().asString();

                    boolean isSystem = false;
                    boolean isAddedIn = false;
                    boolean isAddedInOrBefore = false;
                    boolean isHidden = false;
                    if (!method.getJavadoc().isEmpty()) {
                        isHidden = method.getJavadoc().get().toText().contains("@hide");
                    }

                    NodeList<AnnotationExpr> annotations = method.getAnnotations();
                    for (int j = 0; j < annotations.size(); j++) {
                        String annotationString = annotations.get(j).getName().asString();
                        if (annotationString.contains("SystemApi")) {
                            isSystem = true;
                        }
                        if (annotationString.contains("AddedInOrBefore")) {
                            isAddedInOrBefore = true;
                        }
                        if (annotationString.equals("AddedIn")) {
                            isAddedInOrBefore = true;
                        }
                    }

                    if ((isAddedInOrBefore || isAddedIn) && onlyUnannotated) {
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("method ");

                    if (isHidden) {
                        sb.append("@hide ");
                    }

                    if (isSystem) {
                        sb.append("@SystemApi ");
                    }

                    if (isAddedInOrBefore) {
                        sb.append("@AddedInOrBefore ");
                    }

                    if (isAddedIn) {
                        sb.append("@AddedIn ");
                    }

                    sb.append(returnType);
                    sb.append(" ");
                    sb.append(methodName);
                    sb.append("(");

                    List<Parameter> parameters = method.getParameters();
                    for (int j = 0; j < parameters.size(); j++) {
                        Parameter parameter = parameters.get(j);
                        sb.append(parameter.getTypeAsString());
                        sb.append(" ");
                        sb.append(parameter.getNameAsString());
                        if (j < parameters.size() - 1) {
                            sb.append(", ");
                        }
                    }
                    sb.append(");");
                    if (DBG) {
                        System.out.printf("%s%s\n", TAB, sb);
                    }
                    parsedList.add(TAB + sb);
                }

                if (parsedList.size() == originalSizeOfParseList) {
                    // no method or field is added
                    if (onlyUnannotated) {
                        parsedList.remove(originalSizeOfParseList - 1);
                    }
                }

                super.visit(n, arg);
            }
        }.visit(cu, null);

        return parsedList;
    }
}
