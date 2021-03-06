package se.kth.castor.pankti.generate.generators;

import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.copy.HierarchicalStreamCopier;
import com.thoughtworks.xstream.io.json.JettisonMappedXmlDriver;
import com.thoughtworks.xstream.io.xml.XppReader;
import org.apache.commons.text.StringEscapeUtils;
import org.xmlpull.mxp1.MXParser;
import se.kth.castor.pankti.generate.parsers.InstrumentedMethod;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

public class TestGeneratorUtil {
    public CtMethod<?> generateDeserializationMethod(Factory factory) {
        String methodName = "deserializeObject";
        CtTypeParameter typeParameter = factory.createTypeParameter().setSimpleName("T");
        CtTypeReference typeReference = factory.createCtTypeReference(Object.class).setSimpleName("T");
        CtMethod<?> deserializationMethod = factory.createMethod().setSimpleName(methodName);
        deserializationMethod.setModifiers(Collections.singleton(ModifierKind.PRIVATE));
        deserializationMethod.setFormalCtTypeParameters(Collections.singletonList(typeParameter));
        deserializationMethod.setType(typeReference);
        CtStatement returnStatement = factory.createCodeSnippetStatement("return (T) xStream.fromXML(serializedObjectString)");
        CtBlock<?> methodBody = factory.createBlock();
        methodBody.addStatement(returnStatement);
        deserializationMethod.setBody(methodBody);
        return deserializationMethod;
    }

    public CtLocalVariable<String> addStringVariableToTestMethod(Factory factory, String fieldName, String fieldValue) {
        fieldValue = StringEscapeUtils.escapeJava(fieldValue).replaceAll("\\\\n", "\" +\n\"");
        CtExpression<String> variableExpression = factory.createCodeSnippetExpression(
                "\n\"" + fieldValue + "\""
        );
        return factory.createLocalVariable(
                factory.createCtTypeReference(String.class),
                fieldName,
                variableExpression
        );
    }

    public String getObjectProfileType(String type) {
        return type.substring(0, 1).toUpperCase() + type.substring(1);
    }

    public CtLocalVariable<?> addClassLoaderVariableToTestMethod(Factory factory) {
        CtExpression<String> variableExpression = factory.createCodeSnippetExpression(
                "getClass().getClassLoader()"
        );
        return factory.createLocalVariable(
                factory.createCtTypeReference(ClassLoader.class),
                "classLoader",
                variableExpression
        );
    }

    public CtStatement addFileVariableToTestMethod(Factory factory, String fileName, String type) {
        type = getObjectProfileType(type);
        String fileVariableName = "file" + type;
        // Create file
        CtExpression<String> fileVariableExpression = factory.createCodeSnippetExpression(
                "new File(classLoader.getResource(\"" + fileName + "\").getFile())"
        );
        CtLocalVariable<?> fileVariable = factory.createLocalVariable(
                factory.createCtTypeReference(File.class),
                fileVariableName,
                fileVariableExpression);
        return fileVariable;
    }

    public CtStatementList addAndReadFromScannerInDeserializationMethod(Factory factory) {
        CtStatementList scanningStatements = factory.createStatementList();
        String scannerVariableName = "scanner";
        String objectStringVariable = "serializedObjectString";
        // Create scanner
        CtExpression<String> scannerVariableExpression = factory.createCodeSnippetExpression(
                "new Scanner(serializedObjectFile)"
        );
        CtLocalVariable<?> scannerVariable = factory.createLocalVariable(
                factory.createCtTypeReference(Scanner.class),
                scannerVariableName,
                scannerVariableExpression
        );
        // Read object file from scanner
        CtExpression<String> variableExpression = factory.createCodeSnippetExpression(
                scannerVariableName + ".useDelimiter(\"\\\\A\").next()"
        );
        CtLocalVariable<?> stringVariable = factory.createLocalVariable(
                factory.createCtTypeReference(String.class),
                objectStringVariable,
                variableExpression
        );
        scanningStatements.addStatement(scannerVariable);
        scanningStatements.addStatement(stringVariable);
        return scanningStatements;
    }

    public List<CtStatement> addScannerVariableToTestMethod(Factory factory, String fileName, String type) {
        type = getObjectProfileType(type);
        String fileVariableName = "file" + type;
        String scannerVariableName = "scanner" + type;
        List<CtStatement> fileAndScannerStatements = new ArrayList<>();
        // Create file
        CtExpression<String> fileVariableExpression = factory.createCodeSnippetExpression(
                "new File(classLoader.getResource(\"" + fileName + "\").getFile())"
        );
        CtLocalVariable<?> fileVariable = factory.createLocalVariable(
                factory.createCtTypeReference(File.class),
                fileVariableName,
                fileVariableExpression);
        // Create scanner
        CtExpression<String> scannerVariableExpression = factory.createCodeSnippetExpression(
                "new Scanner(" + fileVariableName + ")"
        );
        CtLocalVariable<?> scannerVariable = factory.createLocalVariable(
                factory.createCtTypeReference(Scanner.class),
                scannerVariableName,
                scannerVariableExpression
        );
        fileAndScannerStatements.add(fileVariable);
        fileAndScannerStatements.add(scannerVariable);
        return fileAndScannerStatements;
    }

    public CtLocalVariable<String> readStringFromScanner(Factory factory, String type) {
        String scannerVariableName = "scanner" + getObjectProfileType(type);
        String xmlVariableName = type + "ObjectStr";
        CtExpression<String> variableExpression = factory.createCodeSnippetExpression(
                scannerVariableName + ".useDelimiter(\"\\\\A\").next()"
        );
        return factory.createLocalVariable(
                factory.createCtTypeReference(String.class),
                xmlVariableName,
                variableExpression
        );
    }

    public String findObjectBoxType(CtTypeReference typeReference) {
        if (typeReference.isPrimitive())
            return typeReference.box().getSimpleName();
        else return typeReference.getQualifiedName().replaceAll("\\$", ".");
    }

    // Gets method param list as _param1,param2,param3
    public String getParamListPostFix(InstrumentedMethod instrumentedMethod) {
        return "_" + String.join(",", instrumentedMethod.getParamList());
    }

    public boolean allMethodParametersArePrimitive(CtMethod<?> method) {
        for (CtParameter<?> parameter : method.getParameters()) {
            if (!parameter.getType().isPrimitive()) {
                return false;
            }
        }
        return true;
    }

    public boolean returnedObjectIsNull(String returnedXML) {
        return returnedXML.equals("<null/>");
    }

    /**
     * This method transforms a xml object string generated by xStream to a json string.
     * The json string should be able to be deserialized to the object by xStream+JettisonMappedXmlDriver
     * (Because the transformation follows the same format that xStream does)
     *
     * @param objectStr the serialized object string in xml
     * @return the identical json string
     */
    public String transformXML2JSON(String objectStr) {
        HierarchicalStreamReader sourceReader = new XppReader(new StringReader(objectStr), new MXParser());

        StringWriter buffer = new StringWriter();
        JettisonMappedXmlDriver jettisonDriver = new JettisonMappedXmlDriver();
        HierarchicalStreamWriter destinationWriter = jettisonDriver.createWriter(buffer);

        HierarchicalStreamCopier copier = new HierarchicalStreamCopier();
        copier.copy(sourceReader, destinationWriter);

        return buffer.toString();
    }
}
