/*
 * ====================================================================
 * Copyright (c) 2004-2010 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.wc17;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNPropertyValue;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryUtil;
import org.tmatesoft.svn.core.internal.util.SVNDate;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNSkel;
import org.tmatesoft.svn.core.internal.wc.ISVNFileFetcher;
import org.tmatesoft.svn.core.internal.wc.ISVNUpdateEditor;
import org.tmatesoft.svn.core.internal.wc.SVNChecksum;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileType;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.internal.wc17.SVNStatus17.ConflictedInfo;
import org.tmatesoft.svn.core.internal.wc17.SVNWCContext.ISVNWCNodeHandler;
import org.tmatesoft.svn.core.internal.wc17.SVNWCContext.NodeCopyFromField;
import org.tmatesoft.svn.core.internal.wc17.SVNWCContext.WritableBaseInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.SVNWCDbKind;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.SVNWCDbStatus;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbAdditionInfo.AdditionInfoField;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbBaseInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbBaseInfo.BaseInfoField;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbInfo.InfoField;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbRepositoryInfo;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb.WCDbRepositoryInfo.RepositoryInfoField;
import org.tmatesoft.svn.core.io.diff.SVNDeltaProcessor;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;
import org.tmatesoft.svn.core.wc.SVNConflictAction;
import org.tmatesoft.svn.core.wc.SVNConflictDescription;
import org.tmatesoft.svn.core.wc.SVNConflictReason;
import org.tmatesoft.svn.core.wc.SVNEvent;
import org.tmatesoft.svn.core.wc.SVNEventAction;
import org.tmatesoft.svn.core.wc.SVNOperation;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNStatusType;
import org.tmatesoft.svn.core.wc.SVNTreeConflictDescription;
import org.tmatesoft.svn.util.SVNLogType;

/**
 * @version 1.3
 * @author TMate Software Ltd.
 */
public class SVNUpdateEditor17 implements ISVNUpdateEditor {

    private SVNWCContext myWcContext;

    private String myTargetBasename;
    private File myAnchorAbspath;
    private File myTargetAbspath;
    private String[] myExtensionPatterns;
    private long myTargetRevision;
    private SVNDepth myRequestedDepth;
    private boolean myIsDepthSticky;
    private boolean myIsUseCommitTimes;
    private boolean myIsRootOpened;
    private boolean myIsTargetDeleted;
    private boolean myIsUnversionedObstructionsAllowed;
    private boolean myIsLockOnDemand;
    private File mySwitchRelpath;
    private SVNURL myReposRootURL;
    private String myReposUuid;
    private List<File> mySkippedTrees = new LinkedList<File>();
    private SVNDeltaProcessor myDeltaProcessor;
    private ISVNFileFetcher myFileFetcher;
    private SVNExternalsStore myExternalsStore;
    private SVNDirectoryInfo myCurrentDirectory;

    private SVNFileInfo myCurrentFile;

    public static ISVNUpdateEditor createUpdateEditor(SVNWCContext wcContext, File anchorAbspath, String target, SVNURL reposRoot, SVNURL switchURL, SVNExternalsStore externalsStore,
            boolean allowUnversionedObstructions, boolean depthIsSticky, SVNDepth depth, String[] preservedExts, ISVNFileFetcher fileFetcher, boolean updateLocksOnDemand) throws SVNException {
        if (depth == SVNDepth.UNKNOWN) {
            depthIsSticky = false;
        }
        WCDbInfo info = wcContext.getDb().readInfo(anchorAbspath, InfoField.reposRootUrl, InfoField.reposUuid);
        assert (info.reposRootUrl != null && info.reposUuid != null);
        if (switchURL != null) {
            if (!SVNPathUtil.isAncestor(info.reposRootUrl.toDecodedString(), switchURL.toDecodedString())) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_INVALID_SWITCH, "''{0}''\nis not the same repository as\n''{1}''", new Object[] {
                        switchURL.toDecodedString(), info.reposRootUrl.toDecodedString()
                });
                SVNErrorManager.error(err, SVNLogType.WC);
            }
        }
        return new SVNUpdateEditor17(wcContext, anchorAbspath, target, info.reposRootUrl, info.reposUuid, switchURL, externalsStore, allowUnversionedObstructions, depthIsSticky, depth, preservedExts,
                fileFetcher, updateLocksOnDemand);
    }

    public SVNUpdateEditor17(SVNWCContext wcContext, File anchorAbspath, String targetBasename, SVNURL reposRootUrl, String reposUuid, SVNURL switchURL, SVNExternalsStore externalsStore,
            boolean allowUnversionedObstructions, boolean depthIsSticky, SVNDepth depth, String[] preservedExts, ISVNFileFetcher fileFetcher, boolean lockOnDemand) {
        myWcContext = wcContext;
        myAnchorAbspath = anchorAbspath;
        myTargetBasename = targetBasename;
        myIsUnversionedObstructionsAllowed = allowUnversionedObstructions;
        myTargetRevision = -1;
        myRequestedDepth = depth;
        myIsDepthSticky = depthIsSticky;
        myDeltaProcessor = new SVNDeltaProcessor();
        myExtensionPatterns = preservedExts;
        myFileFetcher = fileFetcher;
        myTargetAbspath = anchorAbspath;
        myReposRootURL = reposRootUrl;
        myReposUuid = reposUuid;
        myIsLockOnDemand = lockOnDemand;
        myExternalsStore = externalsStore;
        myIsUseCommitTimes = myWcContext.getOptions().isUseCommitTimes();
        if (myTargetBasename != null) {
            myTargetAbspath = SVNFileUtil.createFilePath(myTargetAbspath, myTargetBasename);
        }
        if ("".equals(myTargetBasename)) {
            myTargetBasename = null;
        }
        if (switchURL != null)
            mySwitchRelpath = SVNFileUtil.createFilePath(SVNPathUtil.getRelativePath(reposRootUrl.getPath(), switchURL.getPath()));
        else
            mySwitchRelpath = null;
    }

    public void targetRevision(long revision) throws SVNException {
        myTargetRevision = revision;
    }

    public long getTargetRevision() {
        return myTargetRevision;
    }

    private void rememberSkippedTree(File localAbspath) {
        assert (SVNFileUtil.isAbsolute(localAbspath));
        mySkippedTrees.add(localAbspath);
        return;
    }

    public void openRoot(long revision) throws SVNException {
        boolean already_conflicted;
        myIsRootOpened = true;
        myCurrentDirectory = createDirectoryInfo(null, null, false);
        SVNWCDbKind kind = myWcContext.getDb().readKind(myCurrentDirectory.getLocalAbspath(), true);
        if (kind == SVNWCDbKind.Dir) {
            try {
                already_conflicted = alreadyInATreeConflict(myCurrentDirectory.getLocalAbspath());
            } catch (SVNException e) {
                if (e.getErrorMessage().getErrorCode() == SVNErrorCode.WC_MISSING) {
                    already_conflicted = true;
                } else {
                    throw e;
                }
            }
        } else {
            already_conflicted = false;
        }
        if (already_conflicted) {
            myCurrentDirectory.setSkipThis(true);
            myCurrentDirectory.setSkipDescendants(true);
            myCurrentDirectory.setAlreadyNotified(true);
            myCurrentDirectory.getBumpInfo().setSkipped(true);
            doNotification(myTargetAbspath, SVNNodeKind.UNKNOWN, SVNEventAction.SKIP);
            return;
        }
        if (myTargetBasename == null) {
            WCDbBaseInfo baseInfo = myWcContext.getDb().getBaseInfo(myCurrentDirectory.getLocalAbspath(), BaseInfoField.status, BaseInfoField.depth);
            myCurrentDirectory.setAmbientDepth(baseInfo.depth);
            myCurrentDirectory.setWasIncomplete(baseInfo.status == SVNWCDbStatus.Incomplete);
            myWcContext.getDb().opStartDirectoryUpdateTemp(myCurrentDirectory.getLocalAbspath(), myCurrentDirectory.getNewRelpath(), myTargetRevision);
        }
    }

    private void doNotification(File localAbspath, SVNNodeKind kind, SVNEventAction action) throws SVNException {
        if (myWcContext.getEventHandler() != null) {
            myWcContext.getEventHandler().handleEvent(new SVNEvent(localAbspath, kind, null, -1, null, null, null, null, action, null, null, null, null), 0);
        }
    }

    private boolean alreadyInATreeConflict(File localAbspath) throws SVNException {
        assert (SVNFileUtil.isAbsolute(localAbspath));
        File ancestorAbspath = localAbspath;
        boolean conflicted = false;
        while (true) {
            SVNWCDbStatus status;
            boolean isWcRoot, hasConflict;
            SVNConflictDescription conflict;
            try {
                WCDbInfo readInfo = myWcContext.getDb().readInfo(ancestorAbspath, InfoField.status, InfoField.conflicted);
                status = readInfo.status;
                hasConflict = readInfo.conflicted;
            } catch (SVNException e) {
                if (e.getErrorMessage().getErrorCode() != SVNErrorCode.WC_PATH_NOT_FOUND && e.getErrorMessage().getErrorCode() != SVNErrorCode.WC_NOT_WORKING_COPY
                        && e.getErrorMessage().getErrorCode() != SVNErrorCode.WC_UPGRADE_REQUIRED) {
                    throw e;
                }
                break;
            }
            if (status == SVNWCDbStatus.NotPresent || status == SVNWCDbStatus.Absent || status == SVNWCDbStatus.Excluded)
                break;
            if (hasConflict) {
                conflict = myWcContext.getDb().opReadTreeConflict(ancestorAbspath);
                if (conflict != null) {
                    conflicted = true;
                    break;
                }
            }
            if (SVNFileUtil.getParentFile(ancestorAbspath) == null)
                break;
            isWcRoot = myWcContext.getDb().isWCRoot(ancestorAbspath);
            if (isWcRoot)
                break;
            ancestorAbspath = SVNFileUtil.getParentFile(ancestorAbspath);
        }
        return conflicted;
    }

    public void deleteEntry(String path, long revision) throws SVNException {
        String base = SVNFileUtil.getBasePath(SVNFileUtil.createFilePath(path));
        File localAbspath = SVNFileUtil.createFilePath(myCurrentDirectory.getLocalAbspath(), base);
        if (myCurrentDirectory.isSkipDescendants()) {
            if (!myCurrentDirectory.isSkipThis())
                rememberSkippedTree(localAbspath);
            return;
        }
        checkIfPathIsUnderRoot(path);
        File theirRelpath = SVNFileUtil.createFilePath(myCurrentDirectory.getNewRelpath(), base);
        doEntryDeletion(localAbspath, theirRelpath, myCurrentDirectory.isInDeletedAndTreeConflictedSubtree());
    }

    private void doEntryDeletion(File localAbspath, File theirRelpath, boolean inDeletedAndTreeConflictedSubtree) throws SVNException {
        ISVNWCDb db = myWcContext.getDb();
        boolean conflicted = db.readInfo(localAbspath, InfoField.conflicted).conflicted;
        if (conflicted)
            conflicted = isNodeAlreadyConflicted(localAbspath);
        if (conflicted) {
            rememberSkippedTree(localAbspath);
            doNotification(localAbspath, SVNNodeKind.UNKNOWN, SVNEventAction.SKIP);
            return;
        }
        boolean hidden = db.isNodeHidden(localAbspath);
        if (hidden) {
            db.removeBase(localAbspath);
            if (localAbspath.equals(myTargetAbspath))
                myIsTargetDeleted = true;
            return;
        }
        SVNTreeConflictDescription tree_conflict = null;
        if (!inDeletedAndTreeConflictedSubtree)
            tree_conflict = checkTreeConflict(localAbspath, SVNConflictAction.DELETE, SVNNodeKind.NONE, theirRelpath);
        if (tree_conflict != null) {
            db.opSetTreeConflict(tree_conflict.getPath(), tree_conflict);
            rememberSkippedTree(localAbspath);
            doNotification(localAbspath, SVNNodeKind.UNKNOWN, SVNEventAction.TREE_CONFLICT);
            if (tree_conflict.getConflictReason() == SVNConflictReason.EDITED) {
                db.opMakeCopyTemp(localAbspath, false);
            } else if (tree_conflict.getConflictReason() == SVNConflictReason.DELETED) {
            } else if (tree_conflict.getConflictReason() == SVNConflictReason.REPLACED) {
            } else {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ASSERTION_FAIL);
                SVNErrorManager.error(err, SVNLogType.WC);
            }
        }
        File dirAbspath = SVNFileUtil.getParentFile(localAbspath);
        if (!localAbspath.equals(myTargetAbspath)) {
            SVNSkel workItem = myWcContext.wqBuildBaseRemove(localAbspath, false);
            myWcContext.wqAdd(dirAbspath, workItem);
        } else {
            SVNSkel workItem = myWcContext.wqBuildBaseRemove(localAbspath, true);
            myWcContext.wqAdd(dirAbspath, workItem);
            myIsTargetDeleted = true;
        }
        myWcContext.wqRun(dirAbspath);
        if (tree_conflict == null)
            doNotification(localAbspath, SVNNodeKind.UNKNOWN, SVNEventAction.UPDATE_DELETE);
        return;
    }

    private boolean isNodeAlreadyConflicted(File localAbspath) throws SVNException {
        List<SVNConflictDescription> conflicts = myWcContext.getDb().readConflicts(localAbspath);
        for (SVNConflictDescription cd : conflicts) {
            if (cd.isTreeConflict()) {
                return true;
            } else if (cd.isTreeConflict() || cd.isTextConflict()) {
                ConflictedInfo info = myWcContext.getConflicted(localAbspath, true, true, true);
                return (info.textConflicted || info.propConflicted || info.treeConflicted);
            }
        }
        return false;
    }

    private SVNTreeConflictDescription checkTreeConflict(File localAbspath, SVNConflictAction action, SVNNodeKind theirNodeKind, File theirRelpath) throws SVNException {
        WCDbInfo readInfo = myWcContext.getDb().readInfo(localAbspath, InfoField.status, InfoField.kind, InfoField.haveBase);
        SVNWCDbStatus status = readInfo.status;
        SVNWCDbKind db_node_kind = readInfo.kind;
        boolean have_base = readInfo.haveBase;
        SVNConflictReason reason = null;
        boolean locally_replaced = false;
        boolean modified = false;
        boolean all_mods_are_deletes = false;
        switch (status) {
            case Added:
            case MovedHere:
            case Copied:
                if (have_base) {
                    SVNWCDbStatus base_status = myWcContext.getDb().getBaseInfo(localAbspath, BaseInfoField.status).status;
                    if (base_status != SVNWCDbStatus.NotPresent)
                        locally_replaced = true;
                }
                if (!locally_replaced) {
                    assert (action == SVNConflictAction.ADD);
                    reason = SVNConflictReason.ADDED;
                } else {
                    reason = SVNConflictReason.REPLACED;
                }
                break;

            case Deleted:
                reason = SVNConflictReason.DELETED;
                break;

            case Incomplete:
            case Normal:
                if (action == SVNConflictAction.EDIT)
                    return null;
                switch (db_node_kind) {
                    case File:
                    case Symlink:
                        all_mods_are_deletes = false;
                        modified = hasEntryLocalMods(localAbspath, db_node_kind);
                        break;

                    case Dir:
                        TreeLocalModsInfo info = hasTreeLocalMods(localAbspath);
                        modified = info.modified;
                        all_mods_are_deletes = info.allModsAreDeletes;
                        break;

                    default:
                        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ASSERTION_FAIL);
                        SVNErrorManager.error(err, SVNLogType.WC);
                        break;
                }

                if (modified) {
                    if (all_mods_are_deletes)
                        reason = SVNConflictReason.DELETED;
                    else
                        reason = SVNConflictReason.EDITED;
                }
                break;

            case Absent:
            case Excluded:
            case NotPresent:
                return null;

            case BaseDeleted:
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ASSERTION_FAIL);
                SVNErrorManager.error(err, SVNLogType.WC);
                break;

        }

        if (reason == null)
            return null;
        if (reason == SVNConflictReason.EDITED || reason == SVNConflictReason.DELETED || reason == SVNConflictReason.REPLACED)
            assert (action == SVNConflictAction.EDIT || action == SVNConflictAction.DELETE || action == SVNConflictAction.REPLACE);
        else if (reason == SVNConflictReason.ADDED)
            assert (action == SVNConflictAction.ADD);
        return new SVNTreeConflictDescription(localAbspath, theirNodeKind, action, reason, SVNOperation.UPDATE, null, null);
    }

    private boolean hasEntryLocalMods(File localAbspath, SVNWCDbKind kind) throws SVNException {
        boolean text_modified;
        if (kind == SVNWCDbKind.File || kind == SVNWCDbKind.Symlink) {
            text_modified = myWcContext.isTextModified(localAbspath, false, true);
        } else {
            text_modified = false;
        }
        boolean props_modified = myWcContext.isPropsModified(localAbspath);
        return (text_modified || props_modified);
    }

    private static class TreeLocalModsInfo {

        public boolean modified;
        public boolean allModsAreDeletes;
    }

    private TreeLocalModsInfo hasTreeLocalMods(File localAbspath) throws SVNException {
        final TreeLocalModsInfo modInfo = new TreeLocalModsInfo();
        ISVNWCNodeHandler nodeHandler = new ISVNWCNodeHandler() {

            public void nodeFound(File localAbspath) throws SVNException {
                WCDbInfo readInfo = myWcContext.getDb().readInfo(localAbspath, InfoField.status, InfoField.kind);
                SVNWCDbStatus status = readInfo.status;
                SVNWCDbKind kind = readInfo.kind;
                boolean modified = false;
                if (status != SVNWCDbStatus.Normal)
                    modified = true;
                else if (!modInfo.modified || modInfo.allModsAreDeletes)
                    modified = hasEntryLocalMods(localAbspath, kind);
                if (modified) {
                    modInfo.modified = true;
                    if (status != SVNWCDbStatus.Deleted)
                        modInfo.allModsAreDeletes = false;
                }
                return;
            }
        };
        myWcContext.nodeWalkChildren(localAbspath, nodeHandler, false, SVNDepth.INFINITY);
        return modInfo;
    }

    private void checkIfPathIsUnderRoot(String path) throws SVNException {
        if (SVNFileUtil.isWindows && path != null) {
            String testPath = path.replace(File.separatorChar, '/');
            int ind = -1;

            while (testPath.length() > 0 && (ind = testPath.indexOf("..")) != -1) {
                if (ind == 0 || testPath.charAt(ind - 1) == '/') {
                    int i;
                    for (i = ind + 2; i < testPath.length(); i++) {
                        if (testPath.charAt(i) == '.') {
                            continue;
                        } else if (testPath.charAt(i) == '/') {
                            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Path ''{0}'' is not in the working copy", path);
                            SVNErrorManager.error(err, SVNLogType.WC);
                        } else {
                            break;
                        }
                    }
                    if (i == testPath.length()) {
                        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Path ''{0}'' is not in the working copy", path);
                        SVNErrorManager.error(err, SVNLogType.WC);
                    }
                    testPath = testPath.substring(i);
                } else {
                    testPath = testPath.substring(ind + 2);
                }
            }
        }
    }

    public void absentDir(String path) throws SVNException {
        absentEntry(path, SVNNodeKind.DIR);
    }

    public void absentFile(String path) throws SVNException {
        absentEntry(path, SVNNodeKind.FILE);
    }

    private void absentEntry(String path, SVNNodeKind kind) throws SVNException {
        String name = SVNPathUtil.tail(path);
        SVNWCDbKind dbKind = kind == SVNNodeKind.DIR ? SVNWCDbKind.Dir : SVNWCDbKind.File;
        File localAbspath = SVNFileUtil.createFilePath(myCurrentDirectory.getLocalAbspath(), name);
        SVNNodeKind existing_kind = myWcContext.readKind(localAbspath, true);
        if (existing_kind != SVNNodeKind.NONE) {
            if (myWcContext.isNodeAdded(localAbspath)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Failed to mark ''{0}'' absent: item of the same name is already scheduled for addition", path);
                SVNErrorManager.error(err, SVNLogType.WC);
            }
        }
        WCDbRepositoryInfo baseReposInfo = myWcContext.getDb().scanBaseRepository(myCurrentDirectory.getLocalAbspath(), RepositoryInfoField.relPath, RepositoryInfoField.rootUrl,
                RepositoryInfoField.uuid);
        File reposRelpath = baseReposInfo.relPath;
        SVNURL reposRootUrl = baseReposInfo.rootUrl;
        String reposUuid = baseReposInfo.uuid;
        reposRelpath = SVNFileUtil.createFilePath(reposRelpath, name);
        myWcContext.getDb().addBaseAbsentNode(localAbspath, reposRelpath, reposRootUrl, reposUuid, myTargetRevision, dbKind, SVNWCDbStatus.Absent, null, null);
    }

    public void addDir(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        assert ((copyFromPath != null && SVNRevision.isValidRevisionNumber(copyFromRevision)) || (copyFromPath == null && !SVNRevision.isValidRevisionNumber(copyFromRevision)));
        if (copyFromPath != null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNSUPPORTED_FEATURE, "Failed to add directory ''{0}'': copyfrom arguments not yet supported", path);
            SVNErrorManager.error(err, SVNLogType.WC);
        }
        SVNDirectoryInfo pb = myCurrentDirectory;
        myCurrentDirectory = createDirectoryInfo(myCurrentDirectory, new File(path), true);
        SVNDirectoryInfo db = myCurrentDirectory;
        SVNTreeConflictDescription treeConflict = null;
        if (pb.isSkipDescendants()) {
            if (!pb.isSkipThis())
                rememberSkippedTree(db.getLocalAbspath());
            db.setSkipThis(true);
            db.setSkipDescendants(true);
            db.setAlreadyNotified(true);
            return;
        }
        checkPathUnderRoot(pb.getLocalAbspath(), db.getName());
        if (myTargetAbspath.equals(db.getLocalAbspath())) {
            db.setAmbientDepth((myRequestedDepth == SVNDepth.UNKNOWN) ? SVNDepth.INFINITY : myRequestedDepth);
        } else if (myRequestedDepth == SVNDepth.IMMEDIATES || (myRequestedDepth == SVNDepth.UNKNOWN && pb.getAmbientDepth() == SVNDepth.IMMEDIATES)) {
            db.setAmbientDepth(SVNDepth.EMPTY);
        } else {
            db.setAmbientDepth(SVNDepth.INFINITY);
        }
        if (SVNFileUtil.getAdminDirectoryName().equals(db.getName())) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Failed to add directory ''{0}'': object of the same name as the administrative directory",
                    db.getLocalAbspath());
            SVNErrorManager.error(err, SVNLogType.WC);
            return;
        }
        SVNNodeKind kind = SVNFileType.getNodeKind(SVNFileType.getType(db.getLocalAbspath()));
        SVNWCDbStatus status;
        SVNWCDbKind wc_kind;
        boolean conflicted;
        boolean versionedLocallyAndPresent;
        try {
            WCDbInfo readInfo = myWcContext.getDb().readInfo(db.getLocalAbspath(), InfoField.status, InfoField.kind, InfoField.conflicted);
            status = readInfo.status;
            wc_kind = readInfo.kind;
            conflicted = readInfo.conflicted;
            versionedLocallyAndPresent = isNodePresent(status);
        } catch (SVNException e) {
            if (e.getErrorMessage().getErrorCode() != SVNErrorCode.WC_PATH_NOT_FOUND) {
                throw e;
            }
            wc_kind = SVNWCDbKind.Unknown;
            status = SVNWCDbStatus.Normal;
            conflicted = true;
            versionedLocallyAndPresent = false;
        }
        if (conflicted) {
            conflicted = isNodeAlreadyConflicted(db.getLocalAbspath());
        }
        if (conflicted && status == SVNWCDbStatus.NotPresent && kind == SVNNodeKind.NONE) {
            SVNTreeConflictDescription previous_tc = myWcContext.getTreeConflict(db.getLocalAbspath());
            if (previous_tc != null && previous_tc.getConflictReason() == SVNConflictReason.UNVERSIONED) {
                myWcContext.getDb().opSetTreeConflict(db.getLocalAbspath(), null);
                conflicted = false;
            }
        }
        if (conflicted) {
            rememberSkippedTree(db.getLocalAbspath());
            db.setSkipThis(true);
            db.setSkipDescendants(true);
            db.setAlreadyNotified(true);
            doNotification(db.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.SKIP);
            return;
        }
        if (versionedLocallyAndPresent) {
            boolean local_is_dir;
            boolean local_is_non_dir;
            SVNURL local_is_copy = null;
            if (status == SVNWCDbStatus.Added) {
                local_is_copy = myWcContext.getNodeCopyFromInfo(db.getLocalAbspath(), NodeCopyFromField.rootUrl).rootUrl;
            }
            local_is_dir = (wc_kind == SVNWCDbKind.Dir && status != SVNWCDbStatus.Deleted);
            local_is_non_dir = (wc_kind != SVNWCDbKind.Dir && status != SVNWCDbStatus.Deleted);
            if (local_is_dir) {
                boolean wc_root = false;
                boolean switched = false;
                try {
                    CheckWCRootInfo info = checkWCRoot(db.getLocalAbspath());
                    wc_root = info.wcRoot;
                    switched = info.switched;
                } catch (SVNException e) {
                }
                SVNErrorMessage err = null;
                if (wc_root) {
                    err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Failed to add directory ''{0}'': a separate working copy " + "with the same name already exists",
                            db.getLocalAbspath());
                }
                if (err == null && switched && mySwitchRelpath == null) {
                    err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Switched directory ''{0}'' does not match expected URL ''{1}''", new Object[] {
                            db.getLocalAbspath(), myReposRootURL.appendPath(db.getNewRelpath().getPath(), false)
                    });
                }
                if (err != null) {
                    db.setAlreadyNotified(true);
                    doNotification(db.getLocalAbspath(), SVNNodeKind.DIR, SVNEventAction.UPDATE_OBSTRUCTION);
                    SVNErrorManager.error(err, SVNLogType.WC);
                    return;
                }
            }
            if (local_is_non_dir) {
                db.setAlreadyNotified(true);
                doNotification(db.getLocalAbspath(), SVNNodeKind.DIR, SVNEventAction.UPDATE_OBSTRUCTION);
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Failed to add directory ''{0}'': a non-directory object " + "of the same name already exists",
                        db.getLocalAbspath());
                SVNErrorManager.error(err, SVNLogType.WC);
                return;
            }
            if (!pb.isInDeletedAndTreeConflictedSubtree() && (mySwitchRelpath != null || local_is_non_dir || local_is_copy != null)) {
                treeConflict = checkTreeConflict(db.getLocalAbspath(), SVNConflictAction.ADD, SVNNodeKind.DIR, db.getNewRelpath());
            }
            if (treeConflict == null) {
                db.setAddExisted(true);
            }
        } else if (kind != SVNNodeKind.NONE) {
            db.setObstructionFound(true);
            if (!(kind == SVNNodeKind.DIR && myIsUnversionedObstructionsAllowed)) {
                db.setSkipThis(true);
                myWcContext.getDb().addBaseAbsentNode(db.getLocalAbspath(), db.getNewRelpath(), myReposRootURL, myReposUuid, myTargetRevision != 0 ? myTargetRevision : SVNWCContext.INVALID_REVNUM,
                        SVNWCDbKind.Dir, SVNWCDbStatus.NotPresent, null, null);
                rememberSkippedTree(db.getLocalAbspath());
                treeConflict = createTreeConflict(db.getLocalAbspath(), SVNConflictReason.UNVERSIONED, SVNConflictAction.ADD, SVNNodeKind.DIR, db.getNewRelpath());
                assert (treeConflict != null);
            }
        }
        if (treeConflict != null) {
            myWcContext.getDb().opSetTreeConflict(db.getLocalAbspath(), treeConflict);
            rememberSkippedTree(db.getLocalAbspath());
            db.setSkipThis(true);
            db.setSkipDescendants(true);
            db.setAlreadyNotified(true);
            doNotification(db.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.TREE_CONFLICT);
            return;
        }
        myWcContext.getDb().opSetNewDirToIncompleteTemp(db.getLocalAbspath(), db.getNewRelpath(), myReposRootURL, myReposUuid, myTargetRevision, db.getAmbientDepth());
        prepareDirectory(db, myReposRootURL.appendPath(db.getNewRelpath().getPath(), false), myTargetRevision);
        if (pb.isInDeletedAndTreeConflictedSubtree()) {
            myWcContext.getDb().opDeleteTemp(db.getLocalAbspath());
        }
        if (myWcContext.getEventHandler() != null && !db.isAlreadyNotified() && !db.isAddExisted()) {
            SVNEventAction action;
            if (db.isInDeletedAndTreeConflictedSubtree())
                action = SVNEventAction.UPDATE_ADD_DELETED;
            else if (db.isObstructionFound())
                action = SVNEventAction.UPDATE_EXISTS;
            else
                action = SVNEventAction.UPDATE_ADD;
            db.setAlreadyNotified(true);
            doNotification(db.getLocalAbspath(), SVNNodeKind.DIR, action);
        }
        return;
    }

    public void openDir(String path, long revision) throws SVNException {
        SVNDirectoryInfo pb = myCurrentDirectory;
        myCurrentDirectory = createDirectoryInfo(myCurrentDirectory, new File(path), false);
        SVNDirectoryInfo db = myCurrentDirectory;
        myWcContext.writeCheck(db.getLocalAbspath());
        if (pb.isSkipDescendants()) {
            if (!pb.isSkipThis()) {
                rememberSkippedTree(db.getLocalAbspath());
            }
            db.setSkipThis(true);
            db.setSkipDescendants(true);
            db.setAlreadyNotified(true);
            db.getBumpInfo().setSkipped(true);
            return;
        }
        checkPathUnderRoot(pb.getLocalAbspath(), db.getName());
        WCDbInfo readInfo = myWcContext.getDb().readInfo(db.getLocalAbspath(), InfoField.status, InfoField.revision, InfoField.depth, InfoField.haveWork, InfoField.conflicted);
        SVNWCDbStatus status = readInfo.status;
        db.setOldRevision(readInfo.revision);
        db.setAmbientDepth(readInfo.depth);
        boolean have_work = readInfo.haveWork;
        boolean conflicted = readInfo.conflicted;
        SVNTreeConflictDescription treeConflict = null;
        SVNWCDbStatus baseStatus;
        if (!have_work) {
            baseStatus = status;
        } else {
            WCDbBaseInfo baseInfo = myWcContext.getDb().getBaseInfo(db.getLocalAbspath(), BaseInfoField.status, BaseInfoField.revision, BaseInfoField.depth);
            baseStatus = baseInfo.status;
            db.setOldRevision(baseInfo.revision);
            db.setAmbientDepth(baseInfo.depth);
        }
        db.setWasIncomplete(baseStatus == SVNWCDbStatus.Incomplete);
        if (conflicted) {
            conflicted = isNodeAlreadyConflicted(db.getLocalAbspath());
        }
        if (conflicted) {
            rememberSkippedTree(db.getLocalAbspath());
            db.setSkipThis(true);
            db.setSkipDescendants(true);
            db.setAlreadyNotified(true);
            doNotification(db.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.SKIP);
            return;
        }
        if (!db.isInDeletedAndTreeConflictedSubtree()) {
            treeConflict = checkTreeConflict(db.getLocalAbspath(), SVNConflictAction.EDIT, SVNNodeKind.DIR, db.getNewRelpath());
        }
        if (treeConflict != null) {
            myWcContext.getDb().opSetTreeConflict(db.getLocalAbspath(), treeConflict);
            doNotification(db.getLocalAbspath(), SVNNodeKind.DIR, SVNEventAction.TREE_CONFLICT);
            db.setAlreadyNotified(true);
            if (treeConflict.getConflictReason() != SVNConflictReason.DELETED && treeConflict.getConflictReason() != SVNConflictReason.REPLACED) {
                rememberSkippedTree(db.getLocalAbspath());
                db.setSkipDescendants(true);
                db.setSkipThis(true);
                return;
            } else {
                db.setInDeletedAndTreeConflictedSubtree(true);
            }
        }
        myWcContext.getDb().opStartDirectoryUpdateTemp(db.getLocalAbspath(), db.getNewRelpath(), myTargetRevision);
    }

    public void changeDirProperty(String name, SVNPropertyValue value) throws SVNException {
        if (!myCurrentDirectory.isSkipThis()) {
            myCurrentDirectory.propertyChanged(name, value);
        }
    }

    public void closeDir() throws SVNException {
        SVNDirectoryInfo db = myCurrentDirectory;
        if (db.isSkipThis()) {
            db.getBumpInfo().setSkipped(true);
            maybeBumpDirInfo(db.getBumpInfo());
            return;
        }
        SVNProperties entryProps = db.getChangedEntryProperties();
        SVNProperties davProps = db.getChangedWCProperties();
        SVNProperties regularProps = db.getChangedProperties();

        SVNProperties baseProps = myWcContext.getPristineProps(db.getLocalAbspath());
        SVNProperties actualProps = myWcContext.getActualProps(db.getLocalAbspath());

        if (baseProps == null) {
            baseProps = new SVNProperties();
        }
        if (actualProps == null) {
            actualProps = new SVNProperties();
        }
        SVNStatusType propStatus = SVNStatusType.UNKNOWN;
        SVNProperties newBaseProps = null;
        SVNProperties newActualProps = null;
        long newChangedRev = -1;
        SVNDate newChangedDate = null;
        String newChangedAuthor = null;
        if (db.isWasIncomplete()) {
            if (regularProps == null) {
                regularProps = new SVNProperties();
            }
            for (Iterator names = baseProps.nameSet().iterator(); names.hasNext();) {
                String name = (String) names.next();
                if (!regularProps.containsName(name)) {
                    regularProps.put(name, SVNPropertyValue.create(null));
                }
            }
        }
        if ((regularProps != null && !regularProps.isEmpty()) || (entryProps != null && !entryProps.isEmpty()) || (davProps != null && !davProps.isEmpty())) {
            if (regularProps != null && !regularProps.isEmpty()) {
                if (myExternalsStore != null) {
                    if (regularProps.containsName(SVNProperty.EXTERNALS)) {
                        File path = db.getLocalAbspath();
                        String newValue = regularProps.getStringValue(SVNProperty.EXTERNALS);
                        String oldValue = myWcContext.getProperty(path, SVNProperty.EXTERNALS);
                        if (oldValue == null && newValue == null)
                            ;
                        else if (oldValue != null && newValue != null && oldValue.equals(newValue))
                            ;
                        else if (oldValue != null || newValue != null) {
                            myExternalsStore.addExternal(path, oldValue, newValue);
                            myExternalsStore.addDepth(path, db.getAmbientDepth());
                        }
                    }
                }
                try {
                    newBaseProps = new SVNProperties();
                    newActualProps = new SVNProperties();
                    propStatus = myWcContext.mergeProperties(newBaseProps, newActualProps, db.getLocalAbspath(), SVNWCDbKind.Dir, null, null, null, baseProps, actualProps, regularProps, true, false);
                } catch (SVNException e) {
                    SVNErrorMessage err = e.getErrorMessage().wrap("Couldn't do property merge");
                    SVNErrorManager.error(err, e, SVNLogType.WC);
                }
            }
            AccumulatedChangeInfo change = accumulateLastChange(db.getLocalAbspath(), entryProps);
            newChangedRev = change.changedRev;
            newChangedDate = change.changedDate;
            newChangedAuthor = change.changedAuthor;
        }

        if (db.getParentDir() == null && myTargetBasename != null && !myTargetBasename.equals("")) {
            assert (db.getChangedEntryProperties() == null && db.getChangedWCProperties() == null && db.getChangedProperties() == null);
        } else {

            WCDbBaseInfo baseInfo = myWcContext.getDb().getBaseInfo(db.getLocalAbspath(), BaseInfoField.changedRev, BaseInfoField.changedDate, BaseInfoField.changedAuthor, BaseInfoField.depth);
            long changedRev = baseInfo.changedRev;
            SVNDate changedDate = baseInfo.changedDate;
            String changedAuthor = baseInfo.changedAuthor;
            SVNDepth depth = baseInfo.depth;

            if (SVNRevision.isValidRevisionNumber(newChangedRev)) {
                changedRev = newChangedRev;
            }
            if (newChangedDate != null && newChangedDate.getTime() != 0) {
                changedDate = newChangedDate;
            }
            if (newChangedAuthor != null) {
                changedAuthor = newChangedAuthor;
            }

            if (depth == SVNDepth.UNKNOWN) {
                depth = SVNDepth.INFINITY;
            }

            /*
             * Do we have new properties to install? Or shall we simply retain
             * the prior set of properties? If we're installing new properties,
             * then we also want to write them to an old-style props file.
             */
            SVNProperties props = newBaseProps;
            if (props == null) {
                props = myWcContext.getDb().getBaseProps(db.getLocalAbspath());
            }

            myWcContext.getDb().addBaseDirectory(db.getLocalAbspath(), db.getNewRelpath(), myReposRootURL, myReposUuid, myTargetRevision, props, changedRev, changedDate, changedAuthor, null, depth,
                    (davProps != null && !davProps.isEmpty() ? davProps : null), null, null);
            if (newBaseProps != null) {
                assert (newActualProps != null);
                props = newActualProps;
                SVNProperties propDiffs = propDiffs(newActualProps, newBaseProps);
                if (propDiffs.isEmpty()) {
                    props = null;
                }
                myWcContext.getDb().opSetProps(db.getLocalAbspath(), props, null, null);
            }
        }
        myWcContext.wqRun(db.getLocalAbspath());
        maybeBumpDirInfo(db.getBumpInfo());
        if (db.isAlreadyNotified() && myWcContext.getEventHandler() != null) {
            SVNEventAction action;
            if (db.isInDeletedAndTreeConflictedSubtree()) {
                action = SVNEventAction.UPDATE_UPDATE_DELETED;
            } else if (db.isObstructionFound() || db.isAddExisted()) {
                action = SVNEventAction.UPDATE_EXISTS;
            } else {
                action = SVNEventAction.UPDATE_UPDATE;
            }
            SVNEvent event = new SVNEvent(db.getLocalAbspath(), SVNNodeKind.DIR, null, myTargetRevision, null, propStatus, null, null, action, null, null, null, null);
            event.setPreviousRevision(db.getOldRevision());
            myWcContext.getEventHandler().handleEvent(event, 0);
        }
        SVNBumpDirInfo bdi = db.getBumpInfo();
        while (bdi != null && bdi.getRefCount() == 0) {
            bdi.getEntryInfo().cleanup();
            bdi = bdi.getParent();
        }
        return;
    }

    public void addFile(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        assert ((copyFromPath != null && SVNRevision.isValidRevisionNumber(copyFromRevision)) || (copyFromPath == null && !SVNRevision.isValidRevisionNumber(copyFromRevision)));
        SVNDirectoryInfo pb = myCurrentDirectory;
        SVNFileInfo fb = createFileInfo(pb, new File(path), true);
        myCurrentFile = fb;
        SVNTreeConflictDescription treeConflict = null;
        if (pb.isSkipDescendants()) {
            if (!pb.isSkipThis()) {
                rememberSkippedTree(fb.getLocalAbspath());
            }
            fb.setSkipThis(true);
            fb.setAlreadyNotified(true);
            return;
        }
        checkPathUnderRoot(pb.getLocalAbspath(), fb.getName());
        fb.setDeleted(pb.isInDeletedAndTreeConflictedSubtree());
        if (SVNFileUtil.getAdminDirectoryName().equals(fb.getName())) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Failed to add file ''{0}'' : object of the same name as the administrative directory",
                    fb.getLocalAbspath());
            SVNErrorManager.error(err, SVNLogType.WC);
            return;
        }
        SVNNodeKind kind;
        SVNWCDbKind wcKind;
        SVNWCDbStatus status;
        boolean conflicted;
        boolean versionedLocallyAndPresent;
        kind = SVNFileType.getNodeKind(SVNFileType.getType(fb.getLocalAbspath()));
        try {
            WCDbInfo readInfo = myWcContext.getDb().readInfo(fb.getLocalAbspath(), InfoField.status, InfoField.kind, InfoField.conflicted);
            status = readInfo.status;
            wcKind = readInfo.kind;
            conflicted = readInfo.conflicted;
            versionedLocallyAndPresent = isNodePresent(status);
        } catch (SVNException e) {
            if (e.getErrorMessage().getErrorCode() != SVNErrorCode.WC_PATH_NOT_FOUND) {
                throw e;
            }
            wcKind = SVNWCDbKind.Unknown;
            status = SVNWCDbStatus.Normal;
            conflicted = true;
            versionedLocallyAndPresent = false;
        }
        if (conflicted) {
            conflicted = isNodeAlreadyConflicted(fb.getLocalAbspath());
        }
        if (conflicted && status == SVNWCDbStatus.NotPresent && kind == SVNNodeKind.NONE) {
            SVNTreeConflictDescription previousTc = myWcContext.getTreeConflict(fb.getLocalAbspath());
            if (previousTc != null && previousTc.getConflictReason() == SVNConflictReason.UNVERSIONED) {
                myWcContext.getDb().opSetTreeConflict(fb.getLocalAbspath(), null);
                conflicted = isNodeAlreadyConflicted(fb.getLocalAbspath());
            }
        }
        if (conflicted) {
            rememberSkippedTree(fb.getLocalAbspath());
            fb.setSkipThis(true);
            fb.setAlreadyNotified(true);
            doNotification(fb.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.SKIP);
            return;
        }
        if (versionedLocallyAndPresent) {
            boolean localIsFile;
            boolean isFileExternal;

            if (status == SVNWCDbStatus.Added) {
                status = myWcContext.getDb().scanAddition(fb.getLocalAbspath(), AdditionInfoField.status).status;
            }
            localIsFile = (wcKind == SVNWCDbKind.File || wcKind == SVNWCDbKind.Symlink);
            if (localIsFile) {
                boolean wcRoot = false;
                boolean switched = false;
                try {
                    CheckWCRootInfo checkWCRoot = checkWCRoot(fb.getLocalAbspath());
                    wcRoot = checkWCRoot.wcRoot;
                    switched = checkWCRoot.switched;
                } catch (SVNException e) {

                }
                if (switched && mySwitchRelpath == null) {
                    fb.setAlreadyNotified(true);
                    doNotification(fb.getLocalAbspath(), SVNNodeKind.FILE, SVNEventAction.UPDATE_OBSTRUCTION);
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_OBSTRUCTED_UPDATE, "Switched file ''{0}'' does not match expected URL ''{1}''", new Object[] {
                            fb.getLocalAbspath(), fb.getNewRelpath()
                    });
                    SVNErrorManager.error(err, SVNLogType.WC);
                    return;
                }
            }
            try {
                isFileExternal = myWcContext.isFileExternal(fb.getLocalAbspath());
            } catch (SVNException e) {
                if (e.getErrorMessage().getErrorCode() == SVNErrorCode.WC_PATH_NOT_FOUND) {
                    isFileExternal = false;
                } else {
                    throw e;
                }
            }
            if (!pb.isInDeletedAndTreeConflictedSubtree() && !isFileExternal && (mySwitchRelpath != null || !localIsFile || status != SVNWCDbStatus.Added)) {
                treeConflict = checkTreeConflict(fb.getLocalAbspath(), SVNConflictAction.ADD, SVNNodeKind.FILE, fb.getNewRelpath());
            }
            if (treeConflict == null) {
                fb.setAddExisted(true);
            } else {
                fb.setAddingBaseUnderLocalAdd(true);
            }
        } else if (kind != SVNNodeKind.NONE) {
            fb.setObstructionFound(true);
            if (!(kind == SVNNodeKind.FILE && myIsUnversionedObstructionsAllowed)) {
                fb.setSkipThis(true);
                myWcContext.getDb().addBaseAbsentNode(fb.getLocalAbspath(), fb.getNewRelpath(), myReposRootURL, myReposUuid, myTargetRevision != 0 ? myTargetRevision : SVNWCContext.INVALID_REVNUM,
                        SVNWCDbKind.File, SVNWCDbStatus.NotPresent, null, null);
                rememberSkippedTree(fb.getLocalAbspath());
                treeConflict = createTreeConflict(fb.getLocalAbspath(), SVNConflictReason.UNVERSIONED, SVNConflictAction.ADD, SVNNodeKind.FILE, fb.getNewRelpath());
                assert (treeConflict != null);
            }
        }
        if (treeConflict != null) {
            fb.setObstructionFound(true);
            myWcContext.getDb().opSetTreeConflict(fb.getLocalAbspath(), treeConflict);
            fb.setAlreadyNotified(true);
            doNotification(fb.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.TREE_CONFLICT);
        }
        if (copyFromPath != null && !fb.isSkipThis()) {
            addFileWithHistory(pb, fb, copyFromPath, copyFromRevision);
        }
        return;
    }

    private void addFileWithHistory(SVNDirectoryInfo pb, SVNFileInfo tfb, String copyFromPath, long copyFromRevision) throws SVNException {
        assert (copyFromPath.charAt(0) == '/');
        tfb.setAddedWithHistory(true);
        LocateCopyFromInfo locateCopyFrom = locateCopyFrom(pb.getLocalAbspath(), copyFromPath.substring(1), copyFromRevision);
        InputStream newBaseContents = locateCopyFrom.newBaseContents;
        InputStream newContents = locateCopyFrom.newContents;
        SVNProperties newBaseProps = locateCopyFrom.newBaseProps;
        SVNProperties newProps = locateCopyFrom.newProps;
        WritableBaseInfo openWritableBase = myWcContext.openWritableBase(pb.getLocalAbspath(), true, true);
        OutputStream copiedStream = openWritableBase.stream;
        File copiedTempBaseAbspath = openWritableBase.tempBaseAbspath;
        if (newBaseContents != null && newBaseProps != null) {
            try {
                FSRepositoryUtil.copy(newBaseContents, copiedStream, myWcContext.getEventHandler());
                tfb.setCopiedTextBaseMd5Checksum(openWritableBase.getMD5Checksum());
                tfb.setCopiedTextBaseSha1Checksum(openWritableBase.getSHA1Checksum());
            } finally {
                SVNFileUtil.closeFile(newBaseContents);
                SVNFileUtil.closeFile(copiedStream);
            }
            if (newProps == null)
                newProps = newBaseProps;
        } else {
            if (myFileFetcher == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.WC_INVALID_OP_ON_CWD, "No fetch_func supplied to update_editor.");
                SVNErrorManager.error(err, SVNLogType.DEFAULT);
            }
            newBaseProps = new SVNProperties();
            try {
                myFileFetcher.fetchFile(copyFromPath.substring(1), copyFromRevision, copiedStream, newBaseProps);
                tfb.setCopiedTextBaseMd5Checksum(openWritableBase.getMD5Checksum());
                tfb.setCopiedTextBaseSha1Checksum(openWritableBase.getSHA1Checksum());
            } finally {
                SVNFileUtil.closeFile(copiedStream);
            }
            newBaseProps = newBaseProps.getRegularProperties();
            newProps = newBaseProps;
        }
        myWcContext.getDb().installPristine(copiedTempBaseAbspath, tfb.getCopiedTextBaseMd5Checksum(), tfb.getCopiedTextBaseSha1Checksum());
        tfb.setCopiedBaseProps(newBaseProps);
        tfb.setCopiedWorkingProps(newProps);
        if (newContents != null) {
            File tempDirAbspath = myWcContext.getDb().getWCRootTempDir(pb.getLocalAbspath());
            tfb.setCopiedWorkingText(SVNFileUtil.createUniqueFile(tempDirAbspath, "svn-", null, false));
            OutputStream tmpContents = SVNFileUtil.openFileForWriting(tfb.getCopiedWorkingText());
            try {
                FSRepositoryUtil.copy(newContents, tmpContents, myWcContext.getEventHandler());
            } finally {
                SVNFileUtil.closeFile(tmpContents);
            }
        }
        return;
    }

    public void openFile(String path, long revision) throws SVNException {
        SVNDirectoryInfo pb = myCurrentDirectory;
        SVNFileInfo fb = createFileInfo(pb, new File(path), false);
        myCurrentFile = fb;
        if (pb.isSkipDescendants()) {
            if (!pb.isSkipThis()) {
                rememberSkippedTree(fb.getLocalAbspath());
            }
            fb.setSkipThis(true);
            fb.setAlreadyNotified(true);
            return;
        }
        checkPathUnderRoot(pb.getLocalAbspath(), fb.getName());
        SVNNodeKind kind = SVNFileType.getNodeKind(SVNFileType.getType(fb.getLocalAbspath()));
        WCDbInfo readInfo = myWcContext.getDb().readInfo(fb.getLocalAbspath(), InfoField.revision, InfoField.conflicted);
        fb.setOldRevision(readInfo.revision);
        boolean conflicted = readInfo.conflicted;
        if (conflicted) {
            conflicted = isNodeAlreadyConflicted(fb.getLocalAbspath());
        }
        if (conflicted) {
            rememberSkippedTree(fb.getLocalAbspath());
            fb.setSkipThis(true);
            fb.setAlreadyNotified(true);
            doNotification(fb.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.SKIP);
            return;
        }
        fb.setDeleted(pb.isInDeletedAndTreeConflictedSubtree());
        SVNTreeConflictDescription treeConflict = null;
        if (!pb.isInDeletedAndTreeConflictedSubtree()) {
            treeConflict = checkTreeConflict(fb.getLocalAbspath(), SVNConflictAction.EDIT, SVNNodeKind.FILE, fb.getNewRelpath());
        }
        if (treeConflict != null) {
            myWcContext.getDb().opSetTreeConflict(fb.getLocalAbspath(), treeConflict);
            if (treeConflict.getConflictReason() == SVNConflictReason.DELETED || treeConflict.getConflictReason() == SVNConflictReason.REPLACED) {
                fb.setDeleted(true);
            } else {
                rememberSkippedTree(fb.getLocalAbspath());
            }
            if (!fb.isDeleted()) {
                fb.setSkipThis(true);
            }
            fb.setAlreadyNotified(true);
            doNotification(fb.getLocalAbspath(), SVNNodeKind.UNKNOWN, SVNEventAction.TREE_CONFLICT);
        }
    }

    public void changeFileProperty(String path, String propertyName, SVNPropertyValue propertyValue) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    public void closeFile(String path, String textChecksum) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    public SVNCommitInfo closeEdit() throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    public void abortEdit() throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    public void applyTextDelta(String path, String baseChecksum) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    public OutputStream textDeltaChunk(String path, SVNDiffWindow diffWindow) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    public void textDeltaEnd(String path) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    private SVNDirectoryInfo createDirectoryInfo(SVNDirectoryInfo parent, File path, boolean added) throws SVNException {
        assert (path != null || parent == null);
        SVNDirectoryInfo d = new SVNDirectoryInfo();
        if (path != null) {
            d.setName(SVNFileUtil.getFileName(path));
            d.setLocalAbspath(SVNFileUtil.createFilePath(parent.getLocalAbspath(), d.getName()));
            d.setInDeletedAndTreeConflictedSubtree(parent.isInDeletedAndTreeConflictedSubtree());
        } else {
            d.setLocalAbspath(myAnchorAbspath);
        }
        if (mySwitchRelpath != null) {
            if (parent == null) {
                if (myTargetBasename == null || myTargetBasename.equals("")) {
                    d.setNewRelpath(this.mySwitchRelpath);
                } else {
                    d.setNewRelpath(myWcContext.getDb().scanBaseRepository(d.getLocalAbspath(), RepositoryInfoField.relPath).relPath);
                }
            } else {
                if (parent.getParentDir() == null && myTargetBasename.equals(d.getName()))
                    d.setNewRelpath(mySwitchRelpath);
                else
                    d.setNewRelpath(SVNFileUtil.createFilePath(parent.getNewRelpath(), d.getName()));
            }
        } else {
            if (added) {
                assert (parent != null);
                d.setNewRelpath(SVNFileUtil.createFilePath(parent.getNewRelpath(), d.getName()));
            } else {
                d.setNewRelpath(myWcContext.getDb().scanBaseRepository(d.getLocalAbspath(), RepositoryInfoField.relPath).relPath);
            }
        }
        SVNBumpDirInfo bdi = new SVNBumpDirInfo(d);
        bdi.setParent(parent != null ? parent.getBumpInfo() : null);
        bdi.setRefCount(1);
        bdi.setLocalAbspath(d.getLocalAbspath());
        bdi.setSkipped(false);
        if (parent != null)
            bdi.getParent().setRefCount(bdi.getParent().getRefCount() + 1);
        d.setParentDir(parent);
        d.setObstructionFound(false);
        d.setAddExisted(false);
        d.setBumpInfo(bdi);
        d.setOldRevision(SVNWCContext.INVALID_REVNUM);
        d.setAddingDir(added);
        d.setAmbientDepth(SVNDepth.UNKNOWN);
        d.setWasIncomplete(false);
        myWcContext.registerCleanupHandler(d);
        return d;
    }

    private SVNFileInfo createFileInfo(SVNDirectoryInfo parent, File path, boolean added) throws SVNException {
        assert (path != null);
        SVNFileInfo f = new SVNFileInfo();
        f.setName(SVNFileUtil.getFileName(path));
        f.setOldRevision(SVNWCContext.INVALID_REVNUM);
        f.setLocalAbspath(SVNFileUtil.createFilePath(parent.getLocalAbspath(), f.getName()));
        if (mySwitchRelpath != null) {
            f.setNewRelpath(SVNFileUtil.createFilePath(parent.getNewRelpath(), f.getName()));
        } else {
            f.setNewRelpath(getNodeRelpathIgnoreErrors(f.getLocalAbspath()));
        }
        if (f.getNewRelpath() == null) {
            f.setNewRelpath(SVNFileUtil.createFilePath(parent.getNewRelpath(), f.getName()));
        }
        f.setBumpInfo(parent.getBumpInfo());
        f.setAddingFile(added);
        f.setObstructionFound(false);
        f.setAddExisted(false);
        f.setDeleted(false);
        f.setParentDir(parent);
        f.getBumpInfo().setRefCount(f.getBumpInfo().getRefCount() + 1);
        return f;
    }

    private class SVNBumpDirInfo {

        private final SVNEntryInfo entryInfo;

        private SVNBumpDirInfo parent;
        private int refCount;
        private File localAbspath;
        private boolean skipped;

        public SVNBumpDirInfo(SVNEntryInfo entryInfo) {
            this.entryInfo = entryInfo;
        }

        public SVNEntryInfo getEntryInfo() {
            return entryInfo;
        }

        public SVNBumpDirInfo getParent() {
            return parent;
        }

        public void setParent(SVNBumpDirInfo parent) {
            this.parent = parent;
        }

        public int getRefCount() {
            return refCount;
        }

        public void setRefCount(int refCount) {
            this.refCount = refCount;
        }

        public File getLocalAbspath() {
            return localAbspath;
        }

        public void setLocalAbspath(File localAbspath) {
            this.localAbspath = localAbspath;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public void setSkipped(boolean skipped) {
            this.skipped = skipped;
        }
    };

    private class SVNEntryInfo implements SVNWCContext.CleanupHandler {

        private String name;
        private File localAbspath;
        private File newRelpath;
        private long oldRevision;
        private SVNDirectoryInfo parentDir;
        private boolean skipThis;
        private boolean alreadyNotified;
        private boolean obstructionFound;
        private boolean addExisted;
        private SVNBumpDirInfo bumpInfo;

        private SVNProperties myChangedProperties;
        private SVNProperties myChangedEntryProperties;
        private SVNProperties myChangedWCProperties;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public File getLocalAbspath() {
            return localAbspath;
        }

        public void setLocalAbspath(File localAbspath) {
            this.localAbspath = localAbspath;
        }

        public File getNewRelpath() {
            return newRelpath;
        }

        public void setNewRelpath(File newRelpath) {
            this.newRelpath = newRelpath;
        }

        public long getOldRevision() {
            return oldRevision;
        }

        public void setOldRevision(long oldRevision) {
            this.oldRevision = oldRevision;
        }

        public SVNDirectoryInfo getParentDir() {
            return parentDir;
        }

        public void setParentDir(SVNDirectoryInfo parentDir) {
            this.parentDir = parentDir;
        }

        public boolean isSkipThis() {
            return skipThis;
        }

        public void setSkipThis(boolean skipThis) {
            this.skipThis = skipThis;
        }

        public boolean isAlreadyNotified() {
            return alreadyNotified;
        }

        public void setAlreadyNotified(boolean alreadyNotified) {
            this.alreadyNotified = alreadyNotified;
        }

        public boolean isObstructionFound() {
            return obstructionFound;
        }

        public void setObstructionFound(boolean obstructionFound) {
            this.obstructionFound = obstructionFound;
        }

        public boolean isAddExisted() {
            return addExisted;
        }

        public void setAddExisted(boolean addExisted) {
            this.addExisted = addExisted;
        }

        public SVNBumpDirInfo getBumpInfo() {
            return bumpInfo;
        }

        public void setBumpInfo(SVNBumpDirInfo bumpInfo) {
            this.bumpInfo = bumpInfo;
        }

        public void propertyChanged(String name, SVNPropertyValue value) {
            if (name.startsWith(SVNProperty.SVN_ENTRY_PREFIX)) {
                myChangedEntryProperties = myChangedEntryProperties == null ? new SVNProperties() : myChangedEntryProperties;
                // trim value of svn:entry property
                if (value != null) {
                    String strValue = value.getString();
                    if (strValue != null) {
                        strValue = strValue.trim();
                        value = SVNPropertyValue.create(strValue);
                    }
                }
                myChangedEntryProperties.put(name.substring(SVNProperty.SVN_ENTRY_PREFIX.length()), value);
            } else if (name.startsWith(SVNProperty.SVN_WC_PREFIX)) {
                myChangedWCProperties = myChangedWCProperties == null ? new SVNProperties() : myChangedWCProperties;
                myChangedWCProperties.put(name, value);
            } else {
                myChangedProperties = myChangedProperties == null ? new SVNProperties() : myChangedProperties;
                myChangedProperties.put(name, value);
            }
        }

        public SVNProperties getChangedProperties() {
            return myChangedProperties;
        }

        public void setChangedProperties(SVNProperties changedProperties) {
            myChangedProperties = changedProperties;
        }

        public SVNProperties getChangedEntryProperties() {
            return myChangedEntryProperties;
        }

        public void setChangedEntryProperties(SVNProperties changedEntryProperties) {
            myChangedEntryProperties = changedEntryProperties;
        }

        public SVNProperties getChangedWCProperties() {
            return myChangedWCProperties;
        }

        public void setChangedWCProperties(SVNProperties changedWCProperties) {
            myChangedWCProperties = changedWCProperties;
        }

        public void cleanup() throws SVNException {
        }

    }

    private class SVNDirectoryInfo extends SVNEntryInfo {

        private boolean skipDescendants;
        private boolean addingDir;
        private boolean inDeletedAndTreeConflictedSubtree;
        private SVNDepth ambientDepth;
        private boolean wasIncomplete;

        public boolean isSkipDescendants() {
            return skipDescendants;
        }

        public void setSkipDescendants(boolean skipDescendants) {
            this.skipDescendants = skipDescendants;
        }

        public boolean isAddingDir() {
            return addingDir;
        }

        public void setAddingDir(boolean addingDir) {
            this.addingDir = addingDir;
        }

        public boolean isInDeletedAndTreeConflictedSubtree() {
            return inDeletedAndTreeConflictedSubtree;
        }

        public void setInDeletedAndTreeConflictedSubtree(boolean inDeletedAndTreeConflictedSubtree) {
            this.inDeletedAndTreeConflictedSubtree = inDeletedAndTreeConflictedSubtree;
        }

        public SVNDepth getAmbientDepth() {
            return ambientDepth;
        }

        public void setAmbientDepth(SVNDepth ambientDepth) {
            this.ambientDepth = ambientDepth;
        }

        public boolean isWasIncomplete() {
            return wasIncomplete;
        }

        public void setWasIncomplete(boolean wasIncomplete) {
            this.wasIncomplete = wasIncomplete;
        }

        public void cleanup() throws SVNException {
            SVNUpdateEditor17.this.myWcContext.getDb().runWorkQueue(getLocalAbspath());
        }

    }

    private class SVNFileInfo extends SVNEntryInfo {

        private boolean addingFile;
        private boolean addedWithHistory;
        private boolean deleted;
        private SVNChecksum newTextBaseMd5Checksum;
        private SVNChecksum newTextBaseSha1Checksum;
        private SVNChecksum copiedTextBaseMd5Checksum;
        private SVNChecksum copiedTextBaseSha1Checksum;
        private File copiedWorkingText;
        private SVNProperties copiedBaseProps;
        private SVNProperties copiedWorkingProps;
        private boolean receivedTextdelta;
        private SVNDate lastChangedDate;
        private boolean addingBaseUnderLocalAdd;

        public boolean isAddingFile() {
            return addingFile;
        }

        public void setAddingFile(boolean addingFile) {
            this.addingFile = addingFile;
        }

        public boolean isAddedWithHistory() {
            return addedWithHistory;
        }

        public void setAddedWithHistory(boolean addedWithHistory) {
            this.addedWithHistory = addedWithHistory;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
        }

        public SVNChecksum getNewTextBaseMd5Checksum() {
            return newTextBaseMd5Checksum;
        }

        public void setNewTextBaseMd5Checksum(SVNChecksum newTextBaseMd5Checksum) {
            this.newTextBaseMd5Checksum = newTextBaseMd5Checksum;
        }

        public SVNChecksum getNewTextBaseSha1Checksum() {
            return newTextBaseSha1Checksum;
        }

        public void setNewTextBaseSha1Checksum(SVNChecksum newTextBaseSha1Checksum) {
            this.newTextBaseSha1Checksum = newTextBaseSha1Checksum;
        }

        public SVNChecksum getCopiedTextBaseMd5Checksum() {
            return copiedTextBaseMd5Checksum;
        }

        public void setCopiedTextBaseMd5Checksum(SVNChecksum copiedTextBaseMd5Checksum) {
            this.copiedTextBaseMd5Checksum = copiedTextBaseMd5Checksum;
        }

        public SVNChecksum getCopiedTextBaseSha1Checksum() {
            return copiedTextBaseSha1Checksum;
        }

        public void setCopiedTextBaseSha1Checksum(SVNChecksum copiedTextBaseSha1Checksum) {
            this.copiedTextBaseSha1Checksum = copiedTextBaseSha1Checksum;
        }

        public File getCopiedWorkingText() {
            return copiedWorkingText;
        }

        public void setCopiedWorkingText(File copiedWorkingText) {
            this.copiedWorkingText = copiedWorkingText;
        }

        public SVNProperties getCopiedBaseProps() {
            return copiedBaseProps;
        }

        public void setCopiedBaseProps(SVNProperties copiedBaseProps) {
            this.copiedBaseProps = copiedBaseProps;
        }

        public SVNProperties getCopiedWorkingProps() {
            return copiedWorkingProps;
        }

        public void setCopiedWorkingProps(SVNProperties copiedWorkingProps) {
            this.copiedWorkingProps = copiedWorkingProps;
        }

        public boolean isReceivedTextdelta() {
            return receivedTextdelta;
        }

        public void setReceivedTextdelta(boolean receivedTextdelta) {
            this.receivedTextdelta = receivedTextdelta;
        }

        public SVNDate getLastChangedDate() {
            return lastChangedDate;
        }

        public void setLastChangedDate(SVNDate lastChangedDate) {
            this.lastChangedDate = lastChangedDate;
        }

        public boolean isAddingBaseUnderLocalAdd() {
            return addingBaseUnderLocalAdd;
        }

        public void setAddingBaseUnderLocalAdd(boolean addingBaseUnderLocalAdd) {
            this.addingBaseUnderLocalAdd = addingBaseUnderLocalAdd;
        }

    }

    private static boolean isNodePresent(SVNWCDbStatus status) {
        return status != SVNWCDbStatus.Absent && status != SVNWCDbStatus.Excluded && status != SVNWCDbStatus.NotPresent;
    }

    private void prepareDirectory(SVNDirectoryInfo db, SVNURL ancestorUrl, long ancestorRevision) {
        // TODO
        throw new UnsupportedOperationException();
    }

    private SVNTreeConflictDescription createTreeConflict(File localAbspath, SVNConflictReason unversioned, SVNConflictAction add, SVNNodeKind dir, File newRelpath) {
        // TODO
        throw new UnsupportedOperationException();
    }

    private static class CheckWCRootInfo {

        public boolean wcRoot;
        public SVNWCDbKind kind;
        public boolean switched;
    }

    private CheckWCRootInfo checkWCRoot(File localAbspath) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    private void checkPathUnderRoot(File localAbspath, String name) {
        // TODO
        throw new UnsupportedOperationException();
    }

    private void maybeBumpDirInfo(SVNBumpDirInfo bumpInfo) {
        // TODO
        throw new UnsupportedOperationException();
    }

    private static class AccumulatedChangeInfo {

        public long changedRev;
        public SVNDate changedDate;
        public String changedAuthor;
    }

    private AccumulatedChangeInfo accumulateLastChange(File localAbspath, SVNProperties entryProps) throws SVNException {
        // TODO
        throw new UnsupportedOperationException();
    }

    private SVNProperties propDiffs(SVNProperties newActualProps, SVNProperties newBaseProps) {
        // TODO
        throw new UnsupportedOperationException();
    }

    private File getNodeRelpathIgnoreErrors(File localAbspath) {
        // TODO
        throw new UnsupportedOperationException();
    }

    private static class LocateCopyFromInfo {

        public InputStream newBaseContents;
        public InputStream newContents;
        public SVNProperties newBaseProps;
        public SVNProperties newProps;
    }

    private LocateCopyFromInfo locateCopyFrom(File localAbspath, String copyFromPath, long copyFromRevision) {
        // TODO
        throw new UnsupportedOperationException();
    }

}