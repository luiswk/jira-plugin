package hudson.plugins.jira;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.xml.rpc.ServiceException;
import java.io.IOException;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Wojciech Kaczmarek
 */
public class JiraCustomFieldUpdater extends Notifier {

    private static final String BUILD_PARAMETER_PREFIX = "$";

    public final String customFieldId;
    public final String customFieldValue;

    public String realFieldValue;

    @DataBoundConstructor
    public JiraCustomFieldUpdater(String customFieldId, String customFieldValue) {
        this.customFieldId = customFieldId;
        this.customFieldValue = customFieldValue;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();
        try {
            JiraSession session = getJiraSession(build);

            JiraBuildAction buildAction = build.getAction(JiraBuildAction.class);
            if (buildAction != null && build.getResult().isBetterOrEqualTo(Result.UNSTABLE)) {
                Map<String, String> vars = new HashMap<String, String>();
                vars.putAll(build.getEnvironment(listener));
                vars.putAll(build.getBuildVariables());
                substituteEnvVars(vars);

                List<JiraIssue> issues = Arrays.asList(buildAction.issues);
                submitCustomField(logger, issues, session);
            }
        } catch (ServiceException e) {
            logger.println("Couldn't obtain JIRA session.\n" + e);
            e.printStackTrace();
        }
        return true;
    }

    private void substituteEnvVars(Map<String, String> vars) {
        realFieldValue = customFieldValue;
        // build parameter substitution
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            realFieldValue = substituteEnvVar(realFieldValue, entry.getKey(), entry.getValue());
        }
    }

    private String substituteEnvVar(String origin, String varName, String replacement) {
        String key = BUILD_PARAMETER_PREFIX + varName;
        if (origin != null && origin.contains(key)) {
            return origin.replaceAll(Pattern.quote(key), Matcher.quoteReplacement(replacement));
        }
        return origin;
    }

    private void submitCustomField(PrintStream logger, List<JiraIssue> issues, JiraSession session) {
        List<JiraIssue> copy = new ArrayList<JiraIssue>(issues);
        for (JiraIssue issue : copy) {
            try {
                logger.println("Submitting custom field with id " + customFieldId + " to JIRA issue " + issue.id
                        + " with value: " + realFieldValue);
                session.updateCustomField(issue.id, customFieldId, realFieldValue);
            } catch (RemoteException e) {
                logger.println("Couldn't update JIRA issue " + issue.id + " with new custom field [" + customFieldId
                        + ", " + realFieldValue + "].\n" + e);
            }
        }
    }

    private JiraSession getJiraSession(AbstractBuild<?, ?> build) throws ServiceException, IOException {
        JiraSite site = JiraSite.get(build.getProject());
        if (site == null) {
            throw new IllegalStateException("JIRA site needs to be configured in the project " + build.getFullDisplayName());
        }
        JiraSession session = site.createSession();
        if (session == null) {
            throw new IllegalStateException("Remote SOAP access for JIRA isn't configured in Jenkins");
        }
        return session;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public BuildStepDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(JiraCustomFieldUpdater.class);
        }

        public FormValidation doCheckProjectKey(@QueryParameter String value) throws IOException {
            if (value.length() == 0) {
                return FormValidation.error("Please set the project key");
            }
            return FormValidation.ok();
        }

        @Override
        public JiraCustomFieldUpdater newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(JiraCustomFieldUpdater.class, formData);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Update custom field in affected issues";
        }

    }

    public String getCustomFieldId() {
        return customFieldId;
    }

    public String getCustomFieldValue() {
        return customFieldValue;
    }

    public String getRealFieldValue() {
        return realFieldValue;
    }
}
