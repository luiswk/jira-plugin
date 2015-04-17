package hudson.plugins.jira;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.matrix.MatrixAggregatable;
import hudson.matrix.MatrixAggregator;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.plugins.jira.soap.RemotePermissionException;
import hudson.scm.ChangeLogSet;
import hudson.scm.RepositoryBrowser;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.xml.rpc.ServiceException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Parses build changelog for JIRA issue IDs and then
 * updates JIRA issues accordingly.
 *
 * @author Kohsuke Kawaguchi
 */
public class JiraIssueUpdater extends Recorder implements MatrixAggregatable {

    private static final Logger LOGGER = Logger.getLogger(JiraIssueUpdater.class.getName());

    /**
     * updated jira issue for all status
     *
     * @since 1.22
     */
    private final boolean updateJiraIssueForAllStatus;

    /**
     * True if this JIRA is configured to allow Confluence-style Wiki comment.
     */
    private final boolean supportsWikiStyleComment;

    /**
     * to record scm changes in jira issue
     *
     * @since 1.21
     */
    private final boolean recordScmChanges;

    private UpdaterIssuesSelector issueSelector;

    @DataBoundConstructor
    public JiraIssueUpdater(boolean updateJiraIssueForAllStatus, boolean supportsWikiStyleComment, boolean recordScmChanges,
                            UpdaterIssuesSelector issueSelector) {
        this.updateJiraIssueForAllStatus = updateJiraIssueForAllStatus;
        this.supportsWikiStyleComment = supportsWikiStyleComment;
        this.recordScmChanges = recordScmChanges;
        this.issueSelector = issueSelector;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        // Don't do anything for individual matrix runs.
        if (build instanceof MatrixRun) {
            return true;
        }
        PrintStream logger = listener.getLogger();

        String rootUrl = Hudson.getInstance().getRootUrl();
        if (rootUrl == null) {
            logger.println(Messages.Updater_NoJenkinsUrl());
            build.setResult(Result.FAILURE);
            return true;
        }

        try {
            boolean doUpdate;
            Result buildResult;
            if (updateJiraIssueForAllStatus) {
                doUpdate = true;
                buildResult = Result.FAILURE;
            } else {
                doUpdate = build.getResult().isBetterOrEqualTo(Result.UNSTABLE);
                buildResult = Result.UNSTABLE;
            }

            if (doUpdate) {
                JiraSite site = getJiraSite(build);
                JiraSession session = getJiraSession(site);
                JiraBuildAction buildAction = getJiraBuildAction(build, listener, buildResult);
                if (buildAction != null && buildAction.issues != null && buildAction.issues.length > 0) {
                    List<JiraIssue> issues = Arrays.asList(buildAction.issues);
                    submitComments(build, logger, rootUrl, session,
                            issues, supportsWikiStyleComment, recordScmChanges, site.groupVisibility, site.roleVisibility);
                }
            }
        } catch (ServiceException e) {
            logger.println("Couldn't obtain JIRA session.\n" + e);
            e.printStackTrace();
        }
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    public UpdaterIssuesSelector getIssueSelector() {
        UpdaterIssuesSelector uis = this.issueSelector;
        if (uis == null) uis = new Updater.DefaultUpdaterIssuesSelector();
        return (this.issueSelector = uis);
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public MatrixAggregator createAggregator(MatrixBuild build, Launcher launcher, BuildListener listener) {
        return new MatrixAggregator(build, launcher, listener) {
            @Override
            public boolean endBuild() throws InterruptedException, IOException {
                PrintStream logger = listener.getLogger();
                logger.println("End of Matrix Build. Updating JIRA.");
                return Updater.perform(build, listener, getIssueSelector(), Result.UNSTABLE);
            }
        };
    }

    private JiraSession getJiraSession(JiraSite site) throws ServiceException, IOException {
        JiraSession session = site.getSession();
        if (session == null) {
            throw new IllegalStateException("Remote SOAP access for JIRA isn't configured in Jenkins");
        }
        return session;
    }

    private JiraSite getJiraSite(AbstractBuild<?, ?> build) {
        JiraSite site = JiraSite.get(build.getProject());
        if (site == null) {
            throw new IllegalStateException("JIRA site needs to be configured in the project " + build.getFullDisplayName());
        }
        return site;
    }

    private JiraBuildAction getJiraBuildAction(AbstractBuild<?, ?> build, BuildListener listener, Result buildResult) {
        JiraBuildAction buildAction = build.getAction(JiraBuildAction.class);
        if (buildAction == null) {
            Updater.perform(build, listener, new Updater.DefaultUpdaterIssuesSelector(), buildResult);
            buildAction = build.getAction(JiraBuildAction.class);
        }
        return buildAction;
    }

    /**
     * Submits comments for the given issues.
     * Removes from <code>issues</code> the ones which appear to be invalid.
     *
     * @param build
     * @param logger
     * @param jenkinsRootUrl
     * @param session
     * @param useWikiStyleComments
     * @param recordScmChanges
     * @param groupVisibility
     * @throws RemoteException
     */
    private void submitComments(
            AbstractBuild<?, ?> build, PrintStream logger, String jenkinsRootUrl, JiraSession session, List<JiraIssue> issues,
            boolean useWikiStyleComments, boolean recordScmChanges, String groupVisibility, String roleVisibility) throws RemoteException {
        for (JiraIssue issue : issues) {
            try {
                logger.println(Messages.Updater_Updating(issue.id));
                session.addComment(
                        issue.id,
                        createComment(build, useWikiStyleComments, jenkinsRootUrl, recordScmChanges, issue),
                        groupVisibility, roleVisibility);
            } catch (RemotePermissionException e) {
                // Seems like RemotePermissionException can mean 'no permission' as well as
                // 'issue doesn't exist'.
                // To prevent carrying forward invalid issues forever, we have to drop them
                // even if the cause of the exception was different.
                logger.println("Looks like " + issue.id + " is no valid JIRA issue or you don't have permission to update the issue.\n" +
                        "Issue will not be updated.\n" + e);
                issues.remove(issue);
            }
        }
    }

    /**
     * Creates a comment to be used in JIRA for the build.
     * For example:
     * <pre>
     *  SUCCESS: Integrated in Job #nnnn (See [http://jenkins.domain/job/Job/nnnn/])\r
     *  JIRA-XXXX: Commit message. (Author _author@email.domain_:
     *  [https://bitbucket.org/user/repo/changeset/9af8e4c4c909/])\r
     * </pre>
     */
    private String createComment(AbstractBuild<?, ?> build,
                                        boolean wikiStyle, String jenkinsRootUrl, boolean recordScmChanges, JiraIssue jiraIssue) {
        return format(
                wikiStyle ?
                        "%6$s: Integrated in !%1$simages/16x16/%3$s! [%2$s|%4$s]\n%5$s" :
                        "%6$s: Integrated in %2$s (See [%4$s])\n%5$s",
                jenkinsRootUrl,
                build,
                build.getResult().color.getImage(),
                Util.encode(jenkinsRootUrl + build.getUrl()),
                getScmComments(wikiStyle, build, recordScmChanges, jiraIssue),
                build.getResult().toString());
    }

    private String getScmComments(boolean wikiStyle,
                                         AbstractBuild<?, ?> build, boolean recordScmChanges, JiraIssue jiraIssue) {
        StringBuilder comment = new StringBuilder();
        RepositoryBrowser repoBrowser = getRepositoryBrowser(build);
        for (ChangeLogSet.Entry change : build.getChangeSet()) {
            if (jiraIssue != null && !StringUtils.containsIgnoreCase(change.getMsg(), jiraIssue.id)) {
                continue;
            }
            comment.append(change.getMsg());
            String revision = getRevision(change);
            if (revision != null) {
                URL url = null;
                if (repoBrowser != null) {
                    try {
                        url = repoBrowser.getChangeSetLink(change);
                    } catch (IOException e) {
                        LOGGER.warning("Failed to calculate SCM repository browser link " + e.getMessage());
                    }
                }
                comment.append(" (");
                String uid = change.getAuthor().getId();
                if (StringUtils.isNotBlank(uid)) {
                    comment.append(uid).append(": ");
                }
                if (url != null && StringUtils.isNotBlank(url.toExternalForm())) {
                    if (wikiStyle) {
                        comment.append("[").append(revision).append("|");
                        comment.append(url.toExternalForm()).append("]");
                    } else {
                        comment.append("[").append(url.toExternalForm()).append("]");
                    }
                } else {
                    comment.append("rev ").append(revision);
                }
                comment.append(")");
            }
            comment.append("\n");
            if (recordScmChanges) {
                // see http://issues.jenkins-ci.org/browse/JENKINS-2508
                // added additional try .. catch; getAffectedFiles is not supported by all SCM implementations
                try {
                    for (ChangeLogSet.AffectedFile affectedFile : change.getAffectedFiles()) {
                        comment.append("* ").append(affectedFile.getPath()).append("\n");
                    }
                } catch (UnsupportedOperationException e) {
                    LOGGER.warning("Unsupported SCM operation 'getAffectedFiles'. Fall back to getAffectedPaths.");
                    for (String affectedPath : change.getAffectedPaths()) {
                        comment.append("* ").append(affectedPath).append("\n");
                    }
                }
            }
        }
        return comment.toString();
    }

    private RepositoryBrowser<?> getRepositoryBrowser(AbstractBuild<?, ?> build) {
        if (build.getProject().getScm() != null) {
            return build.getProject().getScm().getEffectiveBrowser();
        }
        return null;
    }

    private String getRevision(ChangeLogSet.Entry entry) {
        String commitId = entry.getCommitId();
        if (commitId != null) {
            return commitId;
        }

        // fall back to old SVN-specific solution, if we have only installed an old subversion-plugin
        // which doesn't implement getCommitId, yet
        try {
            Class<?> clazz = entry.getClass();
            Method method = clazz.getMethod("getRevision", (Class[]) null);
            if (method == null) {
                return null;
            }
            Object revObj = method.invoke(entry, (Object[]) null);
            return (revObj != null) ? revObj.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private DescriptorImpl() {
            super(JiraIssueUpdater.class);
        }

        @Override
        public String getDisplayName() {
            // Displayed in the publisher section
            return Messages.JiraIssueUpdater_DisplayName();

        }

        @Override
        public String getHelpFile() {
            return "/plugin/jira/help.html";
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public boolean hasIssueSelectors() {
            return Jenkins.getInstance().getDescriptorList(UpdaterIssuesSelector.class).size() > 1;
        }
    }
}
