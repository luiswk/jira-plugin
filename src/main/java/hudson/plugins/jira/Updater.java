package hudson.plugins.jira;

import hudson.Extension;
import hudson.model.*;
import hudson.model.AbstractBuild.DependencyChange;
import hudson.plugins.jira.listissuesparameter.JiraIssueParameterValue;
import hudson.scm.ChangeLogSet.Entry;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.xml.rpc.ServiceException;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Actual JIRA update logic.
 *
 * @author Kohsuke Kawaguchi
 */
class Updater {
    static boolean perform(AbstractBuild<?, ?> build, BuildListener listener, UpdaterIssuesSelector selector) {
        PrintStream logger = listener.getLogger();

        JiraSite site = JiraSite.get(build.getProject());
        if (site == null) {
            logger.println(Messages.Updater_NoJiraSite());
            build.setResult(Result.FAILURE);
            return true;
        }

        try {
            Set<String> ids = selector.findIssueIds(build, site, listener);
            if (ids.isEmpty()) {
                if (debug)
                    logger.println("No JIRA issues found.");
                return true;    // nothing found here.
            }

            JiraSession session = null;
            try {
                session = site.createSession();
            } catch (ServiceException e) {
                listener.getLogger().println(Messages.Updater_FailedToConnect());
                e.printStackTrace(listener.getLogger());
            }
            if (session == null) {
                logger.println(Messages.Updater_NoRemoteAccess());
                build.setResult(Result.FAILURE);
                return true;
            }

            List<JiraIssue> issues = getJiraIssues(ids, session, logger);
            build.getActions().add(new JiraBuildAction(build, issues));
        } catch (Exception e) {
            logger.println("Error looking for JIRA issues.\n" + e);
            build.setResult(Result.FAILURE);
        }
        return true;
    }


    private static List<JiraIssue> getJiraIssues(
            Set<String> ids, JiraSession session, PrintStream logger) throws RemoteException {
        List<JiraIssue> issues = new ArrayList<JiraIssue>(ids.size());
        for (String id : ids) {
            if (!session.existsIssue(id)) {
                if (debug) {
                    logger.println(id + " looked like a JIRA issue but it wasn't");
                }
                continue;   // token looked like a JIRA issue but it's actually not.
            }

            issues.add(new JiraIssue(session.getIssue(id)));
        }
        return issues;
    }

    /**
     * Finds the strings that match JIRA issue ID patterns.
     * This method returns all likely candidates and doesn't check
     * if such ID actually exists or not. We don't want to use
     * {@link JiraSite#existsIssue(String)} here so that new projects
     * in JIRA can be detected.
     */
    private static Set<String> findIssueIdsRecursive(AbstractBuild<?, ?> build, Pattern pattern,
                                                     TaskListener listener) {
        Set<String> ids = new HashSet<String>();

        // first, issues that were carried forward.
        Run<?, ?> prev = build.getPreviousBuild();
        if (prev != null) {
            JiraCarryOverAction a = prev.getAction(JiraCarryOverAction.class);
            if (a != null) {
                ids.addAll(a.getIDs());
            }
        }

        // then issues in this build
        findIssues(build, ids, pattern, listener);

        // check for issues fixed in dependencies
        for (DependencyChange depc : build.getDependencyChanges(build.getPreviousBuild()).values()) {
            for (AbstractBuild<?, ?> b : depc.getBuilds()) {
                findIssues(b, ids, pattern, listener);
            }
        }
        return ids;
    }

    /**
     * @param pattern pattern to use to match issue ids
     */
    static void findIssues(AbstractBuild<?, ?> build, Set<String> ids, Pattern pattern,
                           TaskListener listener) {
        for (Entry change : build.getChangeSet()) {
            LOGGER.fine("Looking for JIRA ID in " + change.getMsg());
            Matcher m = pattern.matcher(change.getMsg());

            while (m.find()) {
                if (m.groupCount() >= 1) {
                    String content = StringUtils.upperCase(m.group(1));
                    ids.add(content);
                } else {
                    listener.getLogger().println("Warning: The JIRA pattern " + pattern + " doesn't define a capturing group!");
                }
            }

        }

        // Now look for any JiraIssueParameterValue's set in the build
        // Implements JENKINS-12312
        ParametersAction parameters = build.getAction(ParametersAction.class);

        if (parameters != null) {
            for (ParameterValue val : parameters.getParameters()) {
                if (val instanceof JiraIssueParameterValue) {
                    ids.add(((JiraIssueParameterValue) val).getIssue());
                }
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Updater.class.getName());

    /**
     * Debug flag.
     */
    public static boolean debug = false;

    public static final class DefaultUpdaterIssuesSelector extends UpdaterIssuesSelector {

        @DataBoundConstructor
        public DefaultUpdaterIssuesSelector() {
        }

        @Override
        public Set<String> findIssueIds(@Nonnull final Run<?, ?> run, @Nonnull final JiraSite site, @Nonnull final TaskListener listener) {
            return Updater.findIssueIdsRecursive((AbstractBuild<?, ?>) run, site.getIssuePattern(), listener);
        }

        @Extension
        public static final class DescriptorImpl extends Descriptor<UpdaterIssuesSelector> {
            @Override
            public String getDisplayName() {
                return Messages.Updater_DefaultIssuesSelector();
            }
        }
    }
}
