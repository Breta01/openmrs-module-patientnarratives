<spring:htmlEscape defaultHtmlEscape="true" />
<ul id="menu">
    <li class="first">
        <a href="${pageContext.request.contextPath}/admin"><spring:message
            code="admin.title.short" /></a>
    </li>

    <!-- Add further links here -->
    <li <c:if test='<%= request.getRequestURI().contains("/submitPatientNarrative") %>'>class="active"</c:if>>
    <a href="${pageContext.request.contextPath}/module/patientnarratives/submitPatientNarrative.form"><spring:message
            code="patientnarratives.careseeker.form" /></a>
    </li>

    <!-- Add further links here -->
    <li <c:if test='<%= request.getRequestURI().contains("/careProviderConsole") %>'>class="active"</c:if>>
    <a href="${pageContext.request.contextPath}/module/patientnarratives/careProviderConsole.form"><spring:message
            code="patientnarratives.careprovider.console" /></a>
    </li>



</ul>

<h2>
    <spring:message code="patientnarratives.title" />
</h2>
