/*
 * The MIT License
 *
 * Copyright 2015 Franta Mejta
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.jira;

import java.util.Set;

import javax.annotation.Nonnull;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * Strategy of finding issues which should be updated after completed run.
 *
 * @author Franta Mejta
 */
public abstract class UpdaterIssuesSelector extends AbstractDescribableImpl<UpdaterIssuesSelector> implements ExtensionPoint {

    /**
     * Finds the strings that match JIRA issue ID patterns.
     *
     * This method returns all likely candidates and shouldn't check
     * if such ID actually exists or not.
     *
     * @param run The completed run.
     * @param site Jira site configured for current job.
     * @param listener Current's run listener.
     * @return Set of ids of issues which should be updated.
     */
    public abstract Set<String> findIssueIds(@Nonnull Run<?, ?> run, @Nonnull JiraSite site, @Nonnull TaskListener listener);

}
