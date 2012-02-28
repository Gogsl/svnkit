import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import de.regnis.q.sequence.line.diff.QDiffGenerator;
import de.regnis.q.sequence.line.diff.QDiffGeneratorFactory;
import de.regnis.q.sequence.line.diff.QDiffManager;
import de.regnis.q.sequence.line.diff.QDiffUniGenerator;
import org.tmatesoft.svn.core.internal.util.SVNHashMap;
import org.tmatesoft.svn.core.internal.wc.ISVNReturnValueCallback;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.wc.ISVNOptions;
import org.tmatesoft.svn.core.wc.SVNDiffOptions;
import org.tmatesoft.svn.core.wc2.SvnTarget;
    private SvnTarget originalTarget1;
    private SvnTarget originalTarget2;
    private SvnTarget baseTarget;
    private SvnTarget relativeToTarget;
    private SvnTarget repositoryRoot;
    private boolean forcedBinaryDiff;
    private List<String> rawDiffOptions;
    private boolean forceEmpty;

    private Set<String> visitedPaths;
    private String externalDiffCommand;
    private SVNDiffOptions diffOptions;
    private boolean fallbackToAbsolutePath;
    private ISVNOptions options;

    private String getDisplayPath(SvnTarget target) {
        String relativePath;
        if (baseTarget == null) {
            relativePath = null;
        } else {
            String targetString = target.getPathOrUrlDecodedString();
            String baseTargetString = baseTarget.getPathOrUrlDecodedString();
            relativePath = SVNPathUtil.getRelativePath(baseTargetString, targetString);
        }

        return relativePath != null ? relativePath : target.getPathOrUrlString();
    }

    public SvnDiffGenerator() {
        this.originalTarget1 = null;
        this.originalTarget2 = null;
        this.visitedPaths = new HashSet<String>();
    }

    public void setBaseTarget(SvnTarget baseTarget) {
        this.baseTarget = baseTarget;
    }
    public void setUseGitFormat(boolean useGitFormat) {
        this.useGitFormat = useGitFormat;
    public void init(SvnTarget originalTarget1, SvnTarget originalTarget2) {
        this.originalTarget1 = originalTarget1;
        this.originalTarget2 = originalTarget2;
    }

    public void setRepositoryRoot(SvnTarget repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public void setForceEmpty(boolean forceEmpty) {
        this.forceEmpty = forceEmpty;
    public boolean isForcedBinaryDiff() {
        return forcedBinaryDiff;
    }

    public void setForcedBinaryDiff(boolean forcedBinaryDiff) {
        this.forcedBinaryDiff = forcedBinaryDiff;
    }

    public void displayDeletedDirectory(SvnTarget target, String revision1, String revision2, OutputStream outputStream) throws SVNException {
    public void displayAddedDirectory(SvnTarget target, String revision1, String revision2, OutputStream outputStream) throws SVNException {
    public void displayPropsChanged(SvnTarget target, String revision1, String revision2, boolean dirWasAdded, SVNProperties originalProps, SVNProperties propChanges, OutputStream outputStream) throws SVNException {
        String displayPath = getDisplayPath(target);
        String targetString1 = originalTarget1.getPathOrUrlDecodedString();
        String targetString2 = originalTarget2.getPathOrUrlDecodedString();
        boolean showDiffHeader = !visitedPaths.contains(displayPath);


            if (useGitFormat) {
                targetString1 = adjustRelativeToReposRoot(targetString1);
                targetString2 = adjustRelativeToReposRoot(targetString2);
            }

            String newTargetString = displayPath;
            String newTargetString1 = targetString1;
            String newTargetString2 = targetString2;

            String commonAncestor = SVNPathUtil.getCommonPathAncestor(newTargetString1, newTargetString2);
            int commonLength = commonAncestor == null ? 0 : commonAncestor.length();

            newTargetString1 = newTargetString1.substring(commonLength);
            newTargetString2 = newTargetString2.substring(commonLength);

            newTargetString1 = computeLabel(newTargetString, newTargetString1);
            newTargetString2 = computeLabel(newTargetString, newTargetString2);

            if (relativeToTarget != null) {
                //TODO
            String label1 = getLabel(newTargetString1, revision1);
            String label2 = getLabel(newTargetString2, revision2);
            boolean shouldStopDisplaying = displayHeader(outputStream, displayPath, false, SvnDiffCallback.OperationKind.Modified);
            visitedPaths.add(displayPath);
            if (useGitFormat) {
                displayGitDiffHeader(outputStream, SvnDiffCallback.OperationKind.Modified, displayPath, displayPath, null);
            }
            if (shouldStopDisplaying) {
                String copyFromPath = null;
                SvnDiffCallback.OperationKind operationKind = SvnDiffCallback.OperationKind.Modified;
                label1 = getGitDiffLabel1(operationKind, targetString1, targetString2, copyFromPath, revision1);
                label2 = getGitDiffLabel2(operationKind, targetString1, targetString2, copyFromPath, revision2);
                displayGitDiffHeader(outputStream, operationKind, targetString1, targetString2, copyFromPath);
            displayHeaderFields(outputStream, label1, label2);
        displayPropertyChangesOn(useGitFormat ? targetString1 : displayPath, outputStream);
    private String adjustRelativeToReposRoot(String targetString) {
        if (repositoryRoot != null) {
            String repositoryRootString = repositoryRoot.getPathOrUrlDecodedString();
            String relativePath = SVNPathUtil.getRelativePath(repositoryRootString, targetString);
            return relativePath == null ? "" : relativePath;
        }
        return targetString;
    }

    private String computeLabel(String targetString, String originalTargetString) {
        if (originalTargetString.length() == 0) {
            return targetString;
        } else if (originalTargetString.charAt(0) == '/') {
            return targetString + "\t(..." + originalTargetString + ")";
        } else {
            return targetString + "\t(.../" + originalTargetString + ")";
        }
    }

    public void displayContentChanged(SvnTarget target, File leftFile, File rightFile, String revision1, String revision2, String mimeType1, String mimeType2, SvnDiffCallback.OperationKind operation, File copyFromPath, OutputStream outputStream) throws SVNException {
        ensureEncodingAndEOLSet();
        String displayPath = getDisplayPath(target);

        String targetString1 = originalTarget1.getPathOrUrlDecodedString();
        String targetString2 = originalTarget2.getPathOrUrlDecodedString();

        if (useGitFormat) {
            targetString1 = adjustRelativeToReposRoot(targetString1);
            targetString2 = adjustRelativeToReposRoot(targetString2);
        }

        String newTargetString = displayPath;
        String newTargetString1 = targetString1;
        String newTargetString2 = targetString2;

        String commonAncestor = SVNPathUtil.getCommonPathAncestor(newTargetString1, newTargetString2);
        int commonLength = commonAncestor == null ? 0 : commonAncestor.length();

        newTargetString1 = newTargetString1.substring(commonLength);
        newTargetString2 = newTargetString2.substring(commonLength);

        newTargetString1 = computeLabel(newTargetString, newTargetString1);
        newTargetString2 = computeLabel(newTargetString, newTargetString2);

        if (relativeToTarget != null) {
            //TODO
        }

        String label1 = getLabel(newTargetString1, revision1);
        String label2 = getLabel(newTargetString2, revision2);

        boolean leftIsBinary = false;
        boolean rightIsBinary = false;

        if (mimeType1 != null) {
            leftIsBinary = SVNProperty.isBinaryMimeType(mimeType1);
        }
        if (mimeType2 != null) {
            rightIsBinary = SVNProperty.isBinaryMimeType(mimeType2);
        }

        if (!forcedBinaryDiff && (leftIsBinary || rightIsBinary)) {
            boolean shouldStopDisplaying = displayHeader(outputStream, displayPath, rightFile == null, operation);
            if (useGitFormat) {
                displayGitDiffHeader(outputStream, operation, displayPath, displayPath, null);
            }
            visitedPaths.add(displayPath);
            if (shouldStopDisplaying) {
                return;
            }



            displayBinary(mimeType1, mimeType2, outputStream, leftIsBinary, rightIsBinary);

            return;
        }

        final String diffCommand = getExternalDiffCommand();
        if (diffCommand != null) {
            boolean shouldStopDisplaying = displayHeader(outputStream, displayPath, rightFile == null, operation);
            if (useGitFormat) {
                displayGitDiffHeader(outputStream, operation, displayPath, displayPath, null);
            }
            visitedPaths.add(displayPath);
            if (shouldStopDisplaying) {
                return;
            }

            runExternalDiffCommand(outputStream, diffCommand, leftFile, rightFile, label1, label2);
        } else {
            internalDiff(outputStream, displayPath, leftFile, rightFile, label1, label2, operation, copyFromPath == null ? null : getDisplayPath(SvnTarget.fromFile(copyFromPath)));
        }
    }

    private void displayBinary(String mimeType1, String mimeType2, OutputStream outputStream, boolean leftIsBinary, boolean rightIsBinary) throws SVNException {
        displayCannotDisplayFileMarkedBinary(outputStream);

        if (leftIsBinary && !rightIsBinary) {
            displayMimeType(outputStream, mimeType1);
        } else if (!leftIsBinary && rightIsBinary) {
            displayMimeType(outputStream, mimeType2);
        } else if (leftIsBinary && rightIsBinary) {
            if (mimeType1.equals(mimeType2)) {
                displayMimeType(outputStream, mimeType1);
            } else {
                displayMimeTypes(outputStream, mimeType1, mimeType2);
            }
        }
    }

    private void internalDiff(OutputStream outputStream, String displayPath, File file1, File file2, String label1, String label2, SvnDiffCallback.OperationKind operation, String copyFromPath) throws SVNException {
        String header = getHeaderString(displayPath, label1, label2, operation, copyFromPath);
        String headerFields = getHeaderFieldsString(displayPath, label1, label2);

        RandomAccessFile is1 = null;
        RandomAccessFile is2 = null;
        try {
            is1 = file1 == null ? null : SVNFileUtil.openRAFileForReading(file1);
            is2 = file2 == null ? null : SVNFileUtil.openRAFileForReading(file2);

            QDiffUniGenerator.setup();
            Map properties = new SVNHashMap();

            properties.put(QDiffGeneratorFactory.IGNORE_EOL_PROPERTY, Boolean.valueOf(getDiffOptions().isIgnoreEOLStyle()));
            if (getDiffOptions().isIgnoreAllWhitespace()) {
                properties.put(QDiffGeneratorFactory.IGNORE_SPACE_PROPERTY, QDiffGeneratorFactory.IGNORE_ALL_SPACE);
            } else if (getDiffOptions().isIgnoreAmountOfWhitespace()) {
                properties.put(QDiffGeneratorFactory.IGNORE_SPACE_PROPERTY, QDiffGeneratorFactory.IGNORE_SPACE_CHANGE);
            }

            final String diffHeader;
            if (forceEmpty) {
                displayString(outputStream, header);
                diffHeader = headerFields;

                visitedPaths.add(displayPath);
            } else {
                diffHeader = header + headerFields;
            }
            QDiffGenerator generator = new QDiffUniGenerator(properties, diffHeader);
            EmptyDetectionWriter writer = new EmptyDetectionWriter(new OutputStreamWriter(outputStream, getEncoding()));
            QDiffManager.generateTextDiff(is1, is2, getEncoding(), writer, generator);
            if (writer.isSomethingWritten()) {
                visitedPaths.add(displayPath);
            }
            writer.flush();
        } catch (IOException e) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, e.getMessage());
            SVNErrorManager.error(err, e, SVNLogType.DEFAULT);
        } finally {
            SVNFileUtil.closeFile(is1);
            SVNFileUtil.closeFile(is2);
        }
    }

    private String getHeaderFieldsString(String displayPath, String label1, String label2) throws SVNException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            if (useGitFormat) {

                //
            }
            displayHeaderFields(byteArrayOutputStream, label1, label2);
        } catch (SVNException e) {
            SVNFileUtil.closeFile(byteArrayOutputStream);

            try {
                byteArrayOutputStream.writeTo(byteArrayOutputStream);
            } catch (IOException e1) {
            }

            throw e;
        }

        try {
            byteArrayOutputStream.close();
            return byteArrayOutputStream.toString(getEncoding());
        } catch (IOException e) {
            return "";
        }
    }

    private String getHeaderString(String displayPath, String label1, String label2, SvnDiffCallback.OperationKind operation, String copyFromPath) throws SVNException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            boolean stopDisplaying = displayHeader(byteArrayOutputStream, displayPath, false, operation);
            if (useGitFormat) {
                displayGitDiffHeader(byteArrayOutputStream, operation, displayPath, displayPath, copyFromPath);
            }
            if (stopDisplaying) {
                SVNFileUtil.closeFile(byteArrayOutputStream);
                return null;
            }
//            displayHeaderFields(byteArrayOutputStream, label1, label2);
        } catch (SVNException e) {
            SVNFileUtil.closeFile(byteArrayOutputStream);

            try {
                byteArrayOutputStream.writeTo(byteArrayOutputStream);
            } catch (IOException e1) {
            }

            throw e;
        }

        try {
            byteArrayOutputStream.close();
            return byteArrayOutputStream.toString(getEncoding());
        } catch (IOException e) {
            return "";
        }
    }

    private void runExternalDiffCommand(OutputStream outputStream, final String diffCommand, File file1, File file2, String label1, String label2) throws SVNException {
        final List<String> args = new ArrayList<String>();
        args.add(diffCommand);
        if (rawDiffOptions != null) {
            args.addAll(rawDiffOptions);
        } else {
            Collection svnDiffOptionsCollection = getDiffOptions().toOptionsCollection();
            args.addAll(svnDiffOptionsCollection);
            args.add("-u");
        }

        if (label1 != null) {
            args.add("-L");
            args.add(label1);
        }

        if (label2 != null) {
            args.add("-L");
            args.add(label2);
        }

        boolean tmpFile1 = false;
        boolean tmpFile2 = false;
        if (file1 == null) {
            file1 = SVNFileUtil.createTempFile("svn.", ".tmp");
            tmpFile1 = true;
        }
        if (file2 == null) {
            file2 = SVNFileUtil.createTempFile("svn.", ".tmp");
            tmpFile2 = true;
        }

        String currentDir = new File("").getAbsolutePath().replace(File.separatorChar, '/');
        String file1Path = file1.getAbsolutePath().replace(File.separatorChar, '/');
        String file2Path = file2.getAbsolutePath().replace(File.separatorChar, '/');

        if (file1Path.startsWith(currentDir)) {
            file1Path = file1Path.substring(currentDir.length());
            file1Path = file1Path.startsWith("/") ? file1Path.substring(1) : file1Path;
        }

        if (file2Path.startsWith(currentDir)) {
            file2Path = file2Path.substring(currentDir.length());
            file2Path = file2Path.startsWith("/") ? file2Path.substring(1) : file2Path;
        }

        args.add(file1Path);
        args.add(file2Path);
            try {
                final Writer writer = new OutputStreamWriter(outputStream, getEncoding());

                SVNFileUtil.execCommand(args.toArray(new String[args.size()]), true,
                        new ISVNReturnValueCallback() {

                    public void handleReturnValue(int returnValue) throws SVNException {
                        if (returnValue != 0 && returnValue != 1) {
                            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.EXTERNAL_PROGRAM,
                                    "''{0}'' returned {1}", new Object[] { diffCommand, String.valueOf(returnValue) });
                            SVNErrorManager.error(err, SVNLogType.DEFAULT);
                        }
                    }

                    public void handleChar(char ch) throws SVNException {
                        try {
                            writer.write(ch);
                        } catch (IOException ioe) {
                            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getMessage());
                            SVNErrorManager.error(err, ioe, SVNLogType.DEFAULT);
                        }
                    }

                    public boolean isHandleProgramOutput() {
                        return true;
                    }
                });

                writer.flush();
            } catch (IOException ioe) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getMessage());
                SVNErrorManager.error(err, ioe, SVNLogType.DEFAULT);
            } finally {
                try {
                    if (tmpFile1) {
                        SVNFileUtil.deleteFile(file1);
                    }
                    if (tmpFile2) {
                        SVNFileUtil.deleteFile(file2);
                    }
                } catch (SVNException e) {
                    // skip
                }
            }
    }

    private String getExternalDiffCommand() {
        return externalDiffCommand;
    }

    private void displayMimeType(OutputStream outputStream, String mimeType) throws SVNException {
        try {
            displayString(outputStream, SVNProperty.MIME_TYPE);
            displayString(outputStream, " = ");
            displayString(outputStream, mimeType);
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayMimeTypes(OutputStream outputStream, String mimeType1, String mimeType2) throws SVNException {
        try {
            displayString(outputStream, SVNProperty.MIME_TYPE);
            displayString(outputStream, " = (");
            displayString(outputStream, mimeType1);
            displayString(outputStream, ", ");
            displayString(outputStream, mimeType2);
            displayString(outputStream, ")");
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayCannotDisplayFileMarkedBinary(OutputStream outputStream) throws SVNException {
        try {
            displayString(outputStream, "Cannot display: file marked as a binary type.");
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

                displayString(outputStream, (headerFormat + name));
                displayEOL(outputStream);
                if (SVNProperty.MERGE_INFO.equals(name)) {
                    displayMergeInfoDiff(outputStream, originalValue == null ? null : originalValue.getString(), newValue == null ? null : newValue.getString());
                    continue;
                }

                byte[] originalValueBytes = getPropertyAsBytes(originalValue, getEncoding());
                byte[] newValueBytes = getPropertyAsBytes(newValue, getEncoding());

                if (originalValueBytes == null) {
                    originalValueBytes = new byte[0];
                } else {
                    originalValueBytes = maybeAppendEOL(originalValueBytes);
                }
                if (newValueBytes == null) {
                    newValueBytes = new byte[0];
                } else {
                    newValueBytes = maybeAppendEOL(newValueBytes);
                }

                QDiffUniGenerator.setup();
                Map properties = new SVNHashMap();

                properties.put(QDiffGeneratorFactory.IGNORE_EOL_PROPERTY, Boolean.valueOf(getDiffOptions().isIgnoreEOLStyle()));
                properties.put(QDiffGeneratorFactory.HUNK_DELIMITER, "##");
                if (getDiffOptions().isIgnoreAllWhitespace()) {
                    properties.put(QDiffGeneratorFactory.IGNORE_SPACE_PROPERTY, QDiffGeneratorFactory.IGNORE_ALL_SPACE);
                } else if (getDiffOptions().isIgnoreAmountOfWhitespace()) {
                    properties.put(QDiffGeneratorFactory.IGNORE_SPACE_PROPERTY, QDiffGeneratorFactory.IGNORE_SPACE_CHANGE);
                }

                QDiffGenerator generator = new QDiffUniGenerator(properties, "");
                Writer writer = new OutputStreamWriter(outputStream, getEncoding());
                QDiffManager.generateTextDiff(new ByteArrayInputStream(originalValueBytes), new ByteArrayInputStream(newValueBytes),
                        getEncoding(), writer, generator);
                writer.flush();
    private byte[] maybeAppendEOL(byte[] buffer) {
        if (buffer.length == 0) {
            return buffer;
        }

        byte lastByte = buffer[buffer.length - 1];
        if (lastByte == SVNProperty.EOL_CR_BYTES[0]) {
            return buffer;
        } else if (lastByte != SVNProperty.EOL_LF_BYTES[0]) {
            final byte[] newBuffer = new byte[buffer.length + getEOL().length];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            System.arraycopy(getEOL(), 0, newBuffer, buffer.length, getEOL().length);
            return newBuffer;
        } else {
            return buffer;
        }
    }

    private String getGitDiffLabel1(SvnDiffCallback.OperationKind operationKind, String path1, String path2, String copyFromPath, String revision) {
        if (operationKind == SvnDiffCallback.OperationKind.Deleted) {
            return getLabel("a/" + path1, revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Copied) {
            return getLabel("a/" + copyFromPath, revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Added) {
            return getLabel("/dev/null", revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Modified) {
            return getLabel("a/" + path1, revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Moved) {
            return getLabel("a/" + copyFromPath, revision);
        }
        throw new IllegalArgumentException("Unsupported operation: " + operationKind);
    }

    private String getGitDiffLabel2(SvnDiffCallback.OperationKind operationKind, String path1, String path2, String copyFromPath, String revision) {
        if (operationKind == SvnDiffCallback.OperationKind.Deleted) {
            return getLabel("/dev/null", revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Copied) {
            return getLabel("b/" + path2, revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Added) {
            return getLabel("b/" + path2, revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Modified) {
            return getLabel("b/" + path2, revision);
        } else if (operationKind == SvnDiffCallback.OperationKind.Moved) {
            return getLabel("b/" + path2, revision);
        }
        throw new IllegalArgumentException("Unsupported operation: " + operationKind);
    }

    private void displayGitDiffHeader(OutputStream outputStream, SvnDiffCallback.OperationKind operationKind, String path1, String path2, String copyFromPath) throws SVNException {
        if (operationKind == SvnDiffCallback.OperationKind.Deleted) {
            displayGitDiffHeaderDeleted(outputStream, path1, path2, copyFromPath);
        } else if (operationKind == SvnDiffCallback.OperationKind.Copied) {
            displayGitDiffHeaderCopied(outputStream, path1, path2, copyFromPath);
        } else if (operationKind == SvnDiffCallback.OperationKind.Added) {
            displayGitDiffHeaderAdded(outputStream, path1, path2, copyFromPath);
        } else if (operationKind == SvnDiffCallback.OperationKind.Modified) {
            displayGitDiffHeaderModified(outputStream, path1, path2, copyFromPath);
        } else if (operationKind == SvnDiffCallback.OperationKind.Moved) {
            displayGitDiffHeaderRenamed(outputStream, path1, path2, copyFromPath);
        }
    }

    private void displayGitDiffHeaderAdded(OutputStream outputStream, String path1, String path2, String copyFromPath) throws SVNException {
        try {
            displayString(outputStream, "diff --git a/");
            displayString(outputStream, path1);
            displayString(outputStream, " b/");
            displayString(outputStream, path2);
            displayEOL(outputStream);
            displayString(outputStream, "new file mode 10644");
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayGitDiffHeaderDeleted(OutputStream outputStream, String path1, String path2, String copyFromPath) throws SVNException {
        try {
            displayString(outputStream, "diff --git a/");
            displayString(outputStream, path1);
            displayString(outputStream, " b/");
            displayString(outputStream, path2);
            displayEOL(outputStream);
            displayString(outputStream, "deleted file mode 10644");
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayGitDiffHeaderCopied(OutputStream outputStream, String path1, String path2, String copyFromPath) throws SVNException {
        try {
            displayString(outputStream, "diff --git a/");
            displayString(outputStream, copyFromPath);
            displayString(outputStream, " b/");
            displayString(outputStream, path2);
            displayEOL(outputStream);
            displayString(outputStream, "copy from ");
            displayString(outputStream, copyFromPath);
            displayEOL(outputStream);
            displayString(outputStream, "copy to ");
            displayString(outputStream, path2);
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    }

    private void displayGitDiffHeaderRenamed(OutputStream outputStream, String path1, String path2, String copyFromPath) throws SVNException {
        try {
            displayString(outputStream, "diff --git a/");
            displayString(outputStream, copyFromPath);
            displayString(outputStream, " b/");
            displayString(outputStream, path2);
            displayEOL(outputStream);
            displayString(outputStream, "rename from ");
            displayString(outputStream, copyFromPath);
            displayEOL(outputStream);
            displayString(outputStream, "rename to ");
            displayString(outputStream, path2);
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    private void displayGitDiffHeaderModified(OutputStream outputStream, String path1, String path2, String copyFromPath) throws SVNException {
        try {
            displayString(outputStream, "diff --git a/");
            displayString(outputStream, path1);
            displayString(outputStream, " b/");
            displayString(outputStream, path2);
            displayEOL(outputStream);
        } catch (IOException e) {
            wrapException(e);
        }
    protected boolean displayHeader(OutputStream os, String path, boolean deleted, SvnDiffCallback.OperationKind operation) throws SVNException {
                displayString(os, "Index: ");
                displayString(os, path);
                displayString(os, " (deleted)");
                displayEOL(os);
                displayString(os, HEADER_SEPARATOR);
                displayEOL(os);
            displayString(os, "Index: ");
            displayString(os, path);
            displayEOL(os);
            displayString(os, HEADER_SEPARATOR);
            displayEOL(os);
            displayString(os, "--- ");
            displayString(os, label1);
            displayEOL(os);
            displayString(os, "+++ ");
            displayString(os, label2);
            displayEOL(os);
    private void displayPropertyChangesOn(String path, OutputStream outputStream) throws SVNException {
            displayEOL(outputStream);
            displayString(outputStream, ("Property changes on: " + (useLocalFileSeparatorChar() ? path.replace('/', File.separatorChar) : path)));
            displayEOL(outputStream);
            displayString(outputStream, PROPERTIES_SEPARATOR);
            displayEOL(outputStream);
    private void displayMergeInfoDiff(OutputStream outputStream, String oldValue, String newValue) throws SVNException, IOException {
            displayString(outputStream, ("   Reverse-merged " + path + ":r"));
            displayString(outputStream, rangeList.toString());
            displayEOL(outputStream);
            displayString(outputStream, ("   Merged " + path + ":r"));
            displayString(outputStream, rangeList.toString());
            displayEOL(outputStream);

    private void displayString(OutputStream outputStream, String s) throws IOException {
        outputStream.write(s.getBytes(getEncoding()));
    }

    private void displayEOL(OutputStream os) throws IOException {
        os.write(getEOL());
    }

    public SVNDiffOptions getDiffOptions() {
        if (diffOptions == null) {
            diffOptions = new SVNDiffOptions();
        }
        return diffOptions;
    }

    public void setExternalDiffCommand(String externalDiffCommand) {
        this.externalDiffCommand = externalDiffCommand;
    }

    public void setRawDiffOptions(List<String> rawDiffOptions) {
        this.rawDiffOptions = rawDiffOptions;
    }

    public void setDiffOptions(SVNDiffOptions diffOptions) {
        this.diffOptions = diffOptions;
    }

    public void setDiffDeleted(boolean diffDeleted) {
        this.diffDeleted = diffDeleted;
    }

    public void setBasePath(File absoluteFile) {
        setBaseTarget(SvnTarget.fromFile(absoluteFile));
    }

    public void setFallbackToAbsolutePath(boolean fallbackToAbsolutePath) {
        this.fallbackToAbsolutePath = fallbackToAbsolutePath;
    }

    public void setOptions(ISVNOptions options) {
        this.options = options;
    }

    private class EmptyDetectionWriter extends Writer {

        private final Writer writer;
        private boolean somethingWritten;

        public EmptyDetectionWriter(Writer writer) {
            this.writer = writer;
            this.somethingWritten = false;
        }

        public boolean isSomethingWritten() {
            return somethingWritten;
        }

        @Override
        public void write(int c) throws IOException {
            somethingWritten = true;
            writer.write(c);
        }

        @Override
        public void write(char[] cbuf) throws IOException {
            somethingWritten = cbuf.length > 0;
            writer.write(cbuf);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            somethingWritten = len > 0 && cbuf.length > 0;
            writer.write(cbuf, off, len);
        }

        @Override
        public void write(String str) throws IOException {
            somethingWritten = str.length() > 0;
            writer.write(str);
        }

        @Override
        public void write(String str, int off, int len) throws IOException {
            somethingWritten = len > 0 && str.length() > 0;
            writer.write(str, off, len);
        }

        @Override
        public Writer append(CharSequence csq) throws IOException {
            somethingWritten = csq.length() > 0;
            return writer.append(csq);
        }

        @Override
        public Writer append(CharSequence csq, int start, int end) throws IOException {
            somethingWritten = csq.length() > 0 && (start >= end);
            return writer.append(csq, start, end);
        }

        @Override
        public Writer append(char c) throws IOException {
            somethingWritten = true;
            return writer.append(c);
        }

        @Override
        public void flush() throws IOException {
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }