package com.example.plugins.tutorial.jira.mailhandlerdemo;

import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.service.util.handler.MessageHandler;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.mail.MailUtils;
import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.apache.commons.lang.StringUtils;
import org.ofbiz.core.entity.GenericEntityException;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;


@Scanned
public class DemoHandler implements MessageHandler {
    private String issueKey;

    private final IssueKeyValidator issueKeyValidator;
    private final MessageUserProcessor messageUserProcessor;
    public static final String KEY_ISSUE_KEY = "EISD-1";

    @ComponentImport
    private final IssueManager issueManager;

    @ComponentImport
    private final IssueService issueService;

    @ComponentImport
    private ProjectManager projectManager;

    @ComponentImport
    private ConstantsManager constantsManager;


    public DemoHandler(@ComponentImport MessageUserProcessor messageUserProcessor, IssueKeyValidator issueKeyValidator,
                       IssueManager issueManager, ProjectManager projectManager, IssueService issueService, ConstantsManager constantsManager) {
        this.messageUserProcessor = messageUserProcessor;
        this.issueKeyValidator = issueKeyValidator;
        this.issueManager = issueManager;
        this.projectManager = projectManager;
        this.issueService = issueService;
        this.constantsManager = constantsManager;
    }

    @Override
    public void init(Map<String, String> params, MessageHandlerErrorCollector monitor) {
//        // getting here issue key configured by the user
//        issueKey = params.get(KEY_ISSUE_KEY);
//        if (StringUtils.isBlank(issueKey)) {
//            // this message will be either logged or displayed to the user (if the handler is tested from web UI)
//            monitor.error("Issue key has not been specified ('" + KEY_ISSUE_KEY + "' parameter). This handler will not work correctly.");
//        }
//        issueKeyValidator.validateIssue(issueKey, monitor);
    }

    @Override
    public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException {

        final ApplicationUser sender = messageUserProcessor.getAuthorFromSender(message);
        if (sender == null) {
            context.getMonitor().error("Message sender(s) '" + StringUtils.join(MailUtils.getSenders(message), ",")
                    + "' do not have corresponding users in JIRA. Message will be ignored");
            return false;
        }
        //login user
        ComponentAccessor.getJiraAuthenticationContext().setLoggedInUser(sender);

        Collection<Long> issueListIds = new ArrayList<>();
        final Project project = projectManager.getProjectObjByKey("EISD");
        if (project == null) {
            context.getMonitor().error("Procet does not exist. Message will be ignored");
        }

        boolean commentFlag = false;

        try {
            issueListIds = issueManager.getIssueIdsForProject(project.getId());

        } catch (GenericEntityException e) {
            e.printStackTrace();
        }

        Collection<Issue> issueCollection = new ArrayList<>();
        for (Long l : issueListIds) {
            Issue issue = issueManager.getIssueObject(l);
            issueCollection.add(issue);
        }

        for (Issue issue : issueCollection) {
            if (issue == null) {
                return false;
            }
            String body = MailUtils.getBody(message);
            StringBuilder commentBody = new StringBuilder(message.getSubject());
            if (body != null) {
                commentBody.append("\n").append(StringUtils.abbreviate(body, 100000)); // let trim too long bodies
            }
            if (issue.getSummary().equals(message.getSubject())) {
                context.createComment(issue, sender, commentBody.toString(), false);
                commentFlag = true;
            }
        }
        if (!commentFlag) {
            String body = MailUtils.getBody(message);
            StringBuilder commentBody = new StringBuilder(message.getSubject());
            if (body != null) {
                commentBody.append("\n").append(StringUtils.abbreviate(body, 100000)); // let trim too long bodies
            }
            Collection<IssueType> issueTypes = constantsManager.getAllIssueTypeObjects();
            System.out.println(issueTypes.toString());

            IssueType taskIssueType = constantsManager.getAllIssueTypeObjects().stream().filter(
                    issueType -> issueType.getName().equalsIgnoreCase("task")).findFirst().orElse(null);

            if (taskIssueType == null) {
                context.getMonitor().error("Issue type does not exist. Message will be ignored");
            }

            IssueInputParameters issueInputParameters = issueService.newIssueInputParameters();

            issueInputParameters.setProjectId(project.getId())
                    .setIssueTypeId(taskIssueType.getId())
                    .setSummary(message.getSubject())
                    .setReporterId(sender.getName())
                    .setAssigneeId(sender.getName())
                    .setDescription(body);

            IssueService.CreateValidationResult result = issueService.validateCreate(sender, issueInputParameters);

            if (result.isValid()) {
                IssueService.IssueResult issueResult = issueService.create(sender, result);
                if (!issueResult.isValid()) {
                    context.getMonitor().error("IssueResult is not valid. Message will be ignored");
                }
            } else {
                ErrorCollection errorCollection = result.getErrorCollection();
                context.getMonitor().error("IssueResult is not valid. " + errorCollection.getErrorMessages().toString());
            }

        }

        return true;
    }
}
