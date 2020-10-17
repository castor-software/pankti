package se.kth.castor.pankti.generate.generators;

import se.kth.castor.pankti.generate.parsers.CSVFileParser;
import se.kth.castor.pankti.generate.parsers.InstrumentedMethod;
import se.kth.castor.pankti.generate.parsers.ObjectXMLParser;
import se.kth.castor.pankti.generate.parsers.SerializedObject;
import se.kth.castor.pankti.generate.serializers.ISerializer;
import se.kth.castor.pankti.generate.serializers.Serializer;
import se.kth.castor.pankti.generate.serializers.impl.GSONSerializer;
import se.kth.castor.pankti.generate.serializers.impl.XStreamSerializer;
import spoon.MavenLauncher;
import spoon.compiler.SpoonResource;
import spoon.compiler.SpoonResourceHelper;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class TestGenerator {
    private static Factory factory;
    private static final String XSTREAM_REFERENCE = "com.thoughtworks.xstream.XStream";
    private static final String XSTREAM_CONSTRUCTOR = "new XStream()";
    private static final String JUNIT_TEST_REFERENCE = "org.junit.Test";
    private static final String JUNIT_BEFORE_REFERENCE = "org.junit.Before";
    private static final String JUNIT_ASSERT_REFERENCE = "org.junit.Assert";
    private static final String JAVA_UTIL_ARRAYS_REFERENCE = "java.util.Arrays";
    private static final String JAVA_UTIL_SCANNER_REFERENCE = "java.util.Scanner";
    private static final String JAVA_IO_FILE_REFERENCE = "java.io.File";

    private static final String TEST_CLASS_PREFIX = "Test";
    private static final String TEST_CLASS_POSTFIX = "PanktiGen";
    private static int numberOfTestCasesGenerated;

    private final TestGeneratorUtil testGenUtil = new TestGeneratorUtil();
    private ISerializer testGenSerializer;
    private List<File> classfiles;

    public TestGenerator(Serializer serializer, List<File> classfiles) {
        switch (serializer) {
            case xstream:
                this.testGenSerializer = new XStreamSerializer();
                break;
            case gson:
                this.testGenSerializer = new GSONSerializer();
                break;
        }
        this.classfiles = classfiles;
    }

    public String getGeneratedClassName(CtPackage ctPackage, String className) {
        return String.format("%s.%s%s%s", ctPackage, TEST_CLASS_PREFIX, className, TEST_CLASS_POSTFIX);
    }

    public CtClass<?> generateTestClass(CtPackage ctPackage, String className) {
        CtClass<?> generatedClass = factory.createClass(ctPackage, TEST_CLASS_PREFIX + className + TEST_CLASS_POSTFIX);
        generatedClass.addModifier(ModifierKind.PUBLIC);
        return generatedClass;
    }

    public void addImportsToGeneratedClass(CtClass<?> generatedClass) {
        generatedClass.getFactory().createUnresolvedImport(XSTREAM_REFERENCE, false);
        generatedClass.getFactory().createUnresolvedImport(JUNIT_TEST_REFERENCE, false);
        generatedClass.getFactory().createUnresolvedImport(JUNIT_BEFORE_REFERENCE, false);
        generatedClass.getFactory().createUnresolvedImport(JUNIT_ASSERT_REFERENCE, false);
        generatedClass.getFactory().createUnresolvedImport(JAVA_UTIL_ARRAYS_REFERENCE, false);
        generatedClass.getFactory().createUnresolvedImport(JAVA_UTIL_SCANNER_REFERENCE, false);
        generatedClass.getFactory().createUnresolvedImport(JAVA_IO_FILE_REFERENCE, false);
    }

    public CtField<?> addXStreamFieldToGeneratedClass() throws ClassNotFoundException {
        CtField<?> xStreamField = factory.createCtField(
                "xStream",
                factory.createCtTypeReference(Class.forName(XSTREAM_REFERENCE)),
                XSTREAM_CONSTRUCTOR
        );
        xStreamField.addModifier(ModifierKind.STATIC);
        return xStreamField;
    }

    @SuppressWarnings("unchecked")
    public CtInvocation<?> generateAssertionInTestMethod(CtMethod<?> method, SerializedObject serializedObject) throws ClassNotFoundException {
        CtExpression<?> assertExpectedObject;
        if (method.getType().isPrimitive()) {
            String value = serializedObject.getReturnedObject().replaceAll("</?\\w+>", "");
            assertExpectedObject = factory.createCodeSnippetExpression(value);
        } else {
            assertExpectedObject = factory.createCodeSnippetExpression("expectedObject");
        }

        StringBuilder arguments = new StringBuilder();
        for (int i = 1; i <= method.getParameters().size(); i++) {
            arguments.append("paramObject").append(i);
            if (i != method.getParameters().size()) {
                arguments.append(", ");
            }
        }

        CtExpression<?> assertActualObject = factory.createCodeSnippetExpression(
                String.format("receivingObject.%s(%s)",
                        method.getSimpleName(),
                        arguments.toString()));

        if (method.getVisibility().equals(ModifierKind.PRIVATE)) {
            assertActualObject = factory.createCodeSnippetExpression(
                    String.format("%s%s.invoke(%s, %s)",
                            method.getType().isArray() ? "(" + method.getType() + ") " : "",
                            method.getSimpleName(),
                            "receivingObject",
                            arguments.toString()));
        }

        CtExecutableReference<?> executableReferenceForAssertion = factory.createExecutableReference();
        executableReferenceForAssertion.setStatic(true);
        executableReferenceForAssertion.setDeclaringType(factory.createCtTypeReference(Class.forName(JUNIT_ASSERT_REFERENCE)));
        CtInvocation assertInvocation = factory.createInvocation();

        if (method.getType().isArray()) {
            // if method returns an array, Assert.assertTrue(Arrays.equals(expected, actual))
            executableReferenceForAssertion.setSimpleName("assertTrue");
            assertInvocation.setExecutable(executableReferenceForAssertion);
            assertInvocation.setTarget(factory.createTypeAccess(factory.createCtTypeReference(Class.forName(JUNIT_ASSERT_REFERENCE))));
            CtInvocation arraysEqualsInvocation = factory.createInvocation();
            CtExecutableReference<?> executableReferenceForArraysEquals = factory.createExecutableReference();
            executableReferenceForArraysEquals.setStatic(true);
            executableReferenceForArraysEquals.setDeclaringType(factory.createCtTypeReference(Class.forName(JAVA_UTIL_ARRAYS_REFERENCE)));
            executableReferenceForArraysEquals.setSimpleName("equals");
            arraysEqualsInvocation.setExecutable(executableReferenceForArraysEquals);
            arraysEqualsInvocation.setTarget(factory.createTypeAccess(factory.createCtTypeReference(Class.forName(JAVA_UTIL_ARRAYS_REFERENCE))));
            arraysEqualsInvocation.setArguments(Arrays.asList(assertExpectedObject, assertActualObject));
            assertInvocation.setArguments(Collections.singletonList(arraysEqualsInvocation));
        } else {
            // Assert.assertEquals(expected, actual)
            executableReferenceForAssertion.setSimpleName("assertEquals");
            assertInvocation.setExecutable(executableReferenceForAssertion);
            assertInvocation.setTarget(factory.createTypeAccess(factory.createCtTypeReference(Class.forName(JUNIT_ASSERT_REFERENCE))));
            assertInvocation.setArguments(Arrays.asList(assertExpectedObject, assertActualObject));
        }
        return assertInvocation;
    }

    public String createLongXMLStringFile(String methodIdentifier, String xmlType, String longXML, MavenLauncher launcher) {
        String fileName = "";
        try {
            File longXMLFile = new File("./output/object-data/" + methodIdentifier + "-" + xmlType + ".xml");
            longXMLFile.getParentFile().mkdirs();
            FileWriter myWriter = new FileWriter(longXMLFile);
            myWriter.write(longXML.replaceAll("\\\\\"", "\""));
            myWriter.close();
            SpoonResource newResource = SpoonResourceHelper.createResource(longXMLFile);
            launcher.addInputResource(longXMLFile.getAbsolutePath());
            fileName = newResource.getName();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fileName;
    }

    public List<CtStatement> readXMLFromFile(String fileName, String type) {
        List<CtStatement> createScannerReadString = new ArrayList<>();
        List<CtStatement> scannerDeclaration = testGenUtil.addScannerVariableToTestMethod(factory, fileName, type);
        CtStatement stringReadFromScanner = testGenUtil.readStringFromScanner(factory, type);
        createScannerReadString.addAll(scannerDeclaration);
        createScannerReadString.add(stringReadFromScanner);
        return createScannerReadString;
    }

    public CtStatement parseReceivingObject(String receivingObjectType) {
        return factory.createCodeSnippetStatement(String.format(
                "%s receivingObject = (%s) xStream.fromXML(receivingXML)",
                receivingObjectType,
                receivingObjectType));
    }

    public CtStatement parseReturnedObject(String returnedObjectType, CtMethod<?> method) {
        return factory.createCodeSnippetStatement(String.format(
                "%s expectedObject = (%s) xStream.fromXML(returnedXML)",
                returnedObjectType,
                testGenUtil.findObjectBoxType(method.getType())));
    }

    public List<CtStatement> addAndParseMethodParams(String paramsXML, CtMethod<?> method) {
        List<CtStatement> paramStatements = new ArrayList<>();
        if (paramsXML.length() <= 10000) {
            CtStatement paramsXMLStringDeclaration = testGenUtil.addStringVariableToTestMethod(factory, "paramsXML", paramsXML);
            paramStatements.add(paramsXMLStringDeclaration);
        }
        CtStatement parseParamObjects = factory.createCodeSnippetStatement(
                String.format(
                        "%s paramObjects = (%s) xStream.fromXML(paramsXML)",
                        "Object[]",
                        "Object[]"));

        paramStatements.add(parseParamObjects);

        List<CtParameter<?>> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            CtStatement parseParamObject = factory.createCodeSnippetStatement(
                    String.format("%s paramObject%d = (%s) paramObjects[%d]",
                            parameters.get(i).getType().getQualifiedName(),
                            i + 1,
                            testGenUtil.findObjectBoxType(parameters.get(i).getType()),
                            i));
            paramStatements.add(parseParamObject);
        }
        return paramStatements;
    }

    public List<CtStatement> accessPrivateMethod(InstrumentedMethod instrumentedMethod) {
        List<CtStatement> reflectionStatements = new ArrayList<>();
        // Create Class<?> variable
        CtExpression<String> classVariableExpression = factory.createCodeSnippetExpression(
                "Class.forName(\"" + instrumentedMethod.getParentFQN() + "\")"
        );
        CtLocalVariable<?> classVariable = factory.createLocalVariable(
                factory.createCtTypeReference(Class.class),
                "Clazz",
                classVariableExpression);

        // Create param list
        List<String> paramList = instrumentedMethod.getParamList();
        StringBuilder paramString = new StringBuilder();
        if (paramList.size() > 0) {
            paramString = new StringBuilder(", ");
            for (int i = 0; i < paramList.size(); i++) {
                paramString.append(paramList.get(i)).append(".class");
                if (i != paramList.size() - 1)
                    paramString.append(", ");
            }
        }
        // Create Method variable
        CtExpression<String> methodVariableExpression = factory.createCodeSnippetExpression(
                "Clazz.getDeclaredMethod(\"" + instrumentedMethod.getMethodName() + "\"" + paramString + ")"
        );
        CtLocalVariable<?> methodVariable = factory.createLocalVariable(
                factory.createCtTypeReference(Method.class),
                instrumentedMethod.getMethodName(),
                methodVariableExpression);
        CtStatement setAccessibleStatement = factory.createCodeSnippetStatement(instrumentedMethod.getMethodName() + ".setAccessible(true)");

        reflectionStatements.add(classVariable);
        reflectionStatements.add(methodVariable);
        reflectionStatements.add(setAccessibleStatement);
        return reflectionStatements;
    }

    public List<CtStatement> generateStatementsInMethodBody(InstrumentedMethod instrumentedMethod,
                                                            CtMethod<?> method,
                                                            int methodCounter,
                                                            SerializedObject serializedObject,
                                                            String receivingXML,
                                                            String receivingObjectType,
                                                            String returnedXML,
                                                            String returnedObjectType,
                                                            String paramsXML,
                                                            MavenLauncher launcher) throws ClassNotFoundException {
        List<CtStatement> methodBody = new ArrayList<>();
        String postfix = "";
        if (instrumentedMethod.isOverloaded()) {
            postfix = testGenUtil.getParamListPostFix(instrumentedMethod);
        }
        String methodIdentifier = instrumentedMethod.getFullMethodPath() + postfix + methodCounter;
        if (receivingXML.length() > 10000 || returnedXML.length() > 10000 || paramsXML.length() > 10000) {
            CtStatement classLoaderDeclaration = testGenUtil.addClassLoaderVariableToTestMethod(factory);
            methodBody.add(classLoaderDeclaration);
        }
        if (receivingXML.length() > 10000) {
            String type = "receiving";
            String fileName = createLongXMLStringFile(methodIdentifier, type, receivingXML, launcher);
            List<CtStatement> createScannerReadXML = readXMLFromFile(fileName, type);
            methodBody.addAll(createScannerReadXML);
        } else {
            CtStatement receivingXMLStringDeclaration = testGenUtil.addStringVariableToTestMethod(factory, "receivingXML", receivingXML);
            methodBody.add(receivingXMLStringDeclaration);
        }
        methodBody.add(parseReceivingObject(receivingObjectType));
        if (returnedXML.length() > 10000) {
            String type = "returned";
            String fileName = createLongXMLStringFile(methodIdentifier, type, returnedXML, launcher);
            List<CtStatement> createScannerReadXML = readXMLFromFile(fileName, type);
            methodBody.addAll(createScannerReadXML);
        } else {
            if (!method.getType().isPrimitive()) {
                CtStatement returnedXMLStringDeclaration = testGenUtil.addStringVariableToTestMethod(factory, "returnedXML", returnedXML);
                methodBody.add(returnedXMLStringDeclaration);
            }
        }
        if (!method.getType().isPrimitive())
            methodBody.add(parseReturnedObject(returnedObjectType, method));

        if (!paramsXML.isEmpty()) {
            if (paramsXML.length() > 10000) {
                String type = "params";
                String fileName = createLongXMLStringFile(methodIdentifier, type, paramsXML, launcher);
                List<CtStatement> createScannerReadXML = readXMLFromFile(fileName, type);
                methodBody.addAll(createScannerReadXML);
            }
            List<CtStatement> paramStatements = addAndParseMethodParams(paramsXML, method);
            methodBody.addAll(paramStatements);
        }
        if (instrumentedMethod.getVisibility().equals("private")) {
            methodBody.addAll(accessPrivateMethod(instrumentedMethod));
        }

        methodBody.add(generateAssertionInTestMethod(method, serializedObject));
        return methodBody;
    }

    public CtMethod<?> generateSetupMethod() throws ClassNotFoundException {
        CtMethod<?> generatedMethod = factory.createMethod();
        generatedMethod.setSimpleName("setUpXStream");
        CtAnnotation<?> beforeAnnotation = factory.createAnnotation(factory.createCtTypeReference(Class.forName(JUNIT_BEFORE_REFERENCE)));
        generatedMethod.addAnnotation(beforeAnnotation);
        generatedMethod.setModifiers(Collections.singleton(ModifierKind.PUBLIC));
        generatedMethod.setType(factory.createCtTypeReference(void.class));
        CtBlock<?> methodBody = factory.createBlock();
        CtStatement registerFileConverter = factory.createCodeSnippetStatement("xStream.registerConverter(new FileCleanableConverter())");
        CtStatement registerInflaterConverter = factory.createCodeSnippetStatement("xStream.registerConverter(new InflaterConverter())");
        CtStatement registerCleanerImplConverter = factory.createCodeSnippetStatement("xStream.registerConverter(new CleanerImplConverter())");
        methodBody.addStatement(registerFileConverter);
        methodBody.addStatement(registerInflaterConverter);
        methodBody.addStatement(registerCleanerImplConverter);
        generatedMethod.setBody(methodBody);
        return generatedMethod;
    }

    public CtMethod<?> generateTestMethod(CtMethod<?> method,
                                          int methodCounter,
                                          InstrumentedMethod instrumentedMethod,
                                          SerializedObject serializedObject,
                                          MavenLauncher launcher) throws ClassNotFoundException {
        CtMethod<?> generatedMethod = factory.createMethod();
        String postfix = "";
        if (instrumentedMethod.isOverloaded()) {
            postfix = testGenUtil.getParamListPostFix(instrumentedMethod).replaceAll("[.,]", "_");
        }
        generatedMethod.setSimpleName("test" + method.getSimpleName().substring(0, 1).toUpperCase() + method.getSimpleName().substring(1) + postfix + methodCounter);
        CtAnnotation<?> testAnnotation = factory.createAnnotation(factory.createCtTypeReference(Class.forName(JUNIT_TEST_REFERENCE)));
        generatedMethod.addAnnotation(testAnnotation);
        generatedMethod.setModifiers(Collections.singleton(ModifierKind.PUBLIC));
        generatedMethod.setType(factory.createCtTypeReference(void.class));
        generatedMethod.addThrownType(factory.createCtTypeReference(Exception.class));

        // Get serialized objects as XML strings
        String receivingXML = serializedObject.getReceivingObject();
        String receivingObjectType = serializedObject.getObjectType(receivingXML);
        String returnedXML = serializedObject.getReturnedObject();
        String returnedObjectType = instrumentedMethod.getReturnType();

        String paramsXML = "";
        if (instrumentedMethod.hasParams()) {
            paramsXML = testGenUtil.transformObjectStrings(serializedObject.getParamObjects(), this.testGenSerializer, this.classfiles);
        }

        // Transform the XML strings to the target format based on the chosen serializer
        // (The strings stay the same if the serializer is xStream)
        String receivingObjectStr = testGenUtil.transformObjectStrings(receivingXML, this.testGenSerializer, this.classfiles);
        String returnedObjectStr = testGenUtil.transformObjectStrings(returnedXML, this.testGenSerializer, this.classfiles);
        String paramsObjectStr = "";
        if (paramsXML.length() > 0) {
            paramsObjectStr = testGenUtil.transformObjectStrings(paramsXML, this.testGenSerializer, this.classfiles);
        }

        CtBlock<?> methodBody = factory.createBlock();

        List<CtStatement> statementsInMethodBody =
                generateStatementsInMethodBody(instrumentedMethod, method, methodCounter, serializedObject,
                        receivingObjectStr, receivingObjectType, returnedObjectStr, returnedObjectType, paramsObjectStr, launcher);

        statementsInMethodBody.forEach(methodBody::addStatement);
        generatedMethod.setBody(methodBody);
        return generatedMethod;
}

    public CtClass<?> generateFullTestClass(CtType<?> type,
                                            CtMethod<?> method,
                                            InstrumentedMethod instrumentedMethod,
                                            MavenLauncher launcher,
                                            String objectXMLDirectoryPath) throws ClassNotFoundException {
        String methodPath = instrumentedMethod.getFullMethodPath();
        ObjectXMLParser objectXMLParser = new ObjectXMLParser();
        Set<SerializedObject> serializedObjects = objectXMLParser.parseXML(
                objectXMLDirectoryPath + File.separatorChar + methodPath, instrumentedMethod);
        System.out.println("Number of unique pairs/triples of object values: " + serializedObjects.size());

        if (serializedObjects.size() == 0) {
            System.out.println("Skip generating tests method.");
            return null;
        } else {
            numberOfTestCasesGenerated += serializedObjects.size();

            factory = type.getFactory();
            CtClass<?> generatedClass = factory.Class().get(getGeneratedClassName(type.getPackage(), type.getSimpleName()));
            if (generatedClass == null) {
                generatedClass = generateTestClass(type.getPackage(), type.getSimpleName());
                addImportsToGeneratedClass(generatedClass);
                generatedClass.addField(addXStreamFieldToGeneratedClass());
            }

            // Create @Before method
            // generatedClass.addMethod(generateSetupMethod());

            // Create @Test method
            int methodCounter = 1;
            for (SerializedObject serializedObject : serializedObjects) {
                CtMethod<?> generatedMethod = generateTestMethod(method, methodCounter, instrumentedMethod, serializedObject, launcher);
                generatedClass.addMethod(generatedMethod);
                methodCounter++;
            }
            return generatedClass;
        }

    }

    public List<CtType<?>> getTypesToProcess(CtModel ctModel) {
        List<CtType<?>> types = ctModel.getAllTypes().stream().
                filter(ctType -> ctType.isClass() || ctType.isEnum()).
                collect(Collectors.toList());
        List<CtType<?>> typesToProcess = new ArrayList<>(types);
        for (CtType<?> type : types) {
            typesToProcess.addAll(type.getNestedTypes());
        }
        return typesToProcess;
    }

    private Map.Entry<CtMethod<?>, Boolean> findMethodToGenerateTestMethodsFor(List<CtMethod<?>> methodsByName, InstrumentedMethod instrumentedMethod) {
        if (methodsByName.size() > 1) {
            // match parameter list for overloaded methods
            for (CtMethod<?> method : methodsByName) {
                List<String> paramTypes = method.getParameters().stream().
                        map(parameter -> parameter.getType().getQualifiedName()).
                        collect(Collectors.toList());
                if (Arrays.equals(paramTypes.toArray(), instrumentedMethod.getParamList().toArray())) {
                    System.out.println("matched params " + paramTypes + " for overloaded method " +
                            instrumentedMethod.getFullMethodPath());
                    return new AbstractMap.SimpleEntry<>(method, true);
                }
            }
        }
        return new AbstractMap.SimpleEntry<>(methodsByName.get(0), false);
    }

    public int process(CtModel ctModel, MavenLauncher launcher, String methodCSVFilePath, String objectXMLDirectoryPath) {
        // Get list of instrumented methods from CSV file
        List<InstrumentedMethod> instrumentedMethods = CSVFileParser.parseCSVFile(methodCSVFilePath);
        System.out.println("Number of instrumented methods: " + instrumentedMethods.size());
        System.out.println("--------------------------------------------------------------");
        List<CtType<?>> types = getTypesToProcess(ctModel);

        for (CtType<?> type : types) {
            for (InstrumentedMethod instrumentedMethod : instrumentedMethods) {
                if (type.getQualifiedName().equals(instrumentedMethod.getParentFQN())) {
                    List<CtMethod<?>> methodsByName = type.getMethodsByName(instrumentedMethod.getMethodName());
                    if (methodsByName.size() > 0) {
                        Map.Entry<CtMethod<?>, Boolean> methodAndOverload = findMethodToGenerateTestMethodsFor(methodsByName, instrumentedMethod);
                        CtMethod<?> methodToGenerateTestsFor = methodAndOverload.getKey();
                        instrumentedMethod.setOverloaded(methodAndOverload.getValue());
                        System.out.println("Generating test method for: " + methodToGenerateTestsFor.getPath());
                        try {
                            CtClass<?> generatedClass = generateFullTestClass(
                                    type, methodToGenerateTestsFor, instrumentedMethod,
                                    launcher, objectXMLDirectoryPath);
                            if (generatedClass != null) {
                                System.out.println("Generated test class: " + generatedClass.getQualifiedName());
                            }
                            System.out.println("--------------------------------------------------------------");
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        return numberOfTestCasesGenerated;
    }
}
