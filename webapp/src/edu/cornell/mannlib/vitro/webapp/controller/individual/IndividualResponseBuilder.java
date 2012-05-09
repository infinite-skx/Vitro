/* $This file is distributed under the terms of the license in /doc/license.txt$ */

package edu.cornell.mannlib.vitro.webapp.controller.individual;

import java.util.HashMap;
import java.util.Map;

import edu.cornell.mannlib.vitro.webapp.auth.permissions.SimplePermission;
import edu.cornell.mannlib.vitro.webapp.auth.policy.PolicyHelper;
import edu.cornell.mannlib.vitro.webapp.beans.Individual;
import edu.cornell.mannlib.vitro.webapp.beans.ObjectProperty;
import edu.cornell.mannlib.vitro.webapp.config.ConfigurationProperties;
import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.UrlBuilder;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.ResponseValues;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.TemplateResponseValues;
import edu.cornell.mannlib.vitro.webapp.dao.DisplayVocabulary;
import edu.cornell.mannlib.vitro.webapp.dao.IndividualDao;
import edu.cornell.mannlib.vitro.webapp.dao.ObjectPropertyDao;
import edu.cornell.mannlib.vitro.webapp.dao.VitroVocabulary;
import edu.cornell.mannlib.vitro.webapp.dao.WebappDaoFactory;
import edu.cornell.mannlib.vitro.webapp.web.beanswrappers.ReadOnlyBeansWrapper;
import edu.cornell.mannlib.vitro.webapp.web.templatemodels.individual.IndividualTemplateModel;
import edu.cornell.mannlib.vitro.webapp.web.templatemodels.individuallist.ListedIndividual;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;

/**
 * We have determined that the request is for a normal Individual, and needs an
 * HTML response. Assemble the information for that response.
 * 
 * TODO clean this up.
 */
class IndividualResponseBuilder {
    private static final Map<String, String> namespaces = new HashMap<String, String>() {{
        put("display", VitroVocabulary.DISPLAY);
        put("vitro", VitroVocabulary.vitroURI);
        put("vitroPublic", VitroVocabulary.VITRO_PUBLIC);
    }};

	private final VitroRequest vreq;
	private final WebappDaoFactory wadf;
	private final IndividualDao iDao;
	private final ObjectPropertyDao opDao;

	private final Individual individual;
	
	public IndividualResponseBuilder(VitroRequest vreq, Individual individual) {
		this.vreq = vreq;
		this.wadf = vreq.getWebappDaoFactory();
		this.iDao = wadf.getIndividualDao();
		this.opDao = wadf.getObjectPropertyDao();

		this.individual = individual;
	}

	ResponseValues assembleResponse() throws TemplateModelException {
		Map<String, Object> body = new HashMap<String, Object>();
		
		body.put("title", individual.getName());            
		body.put("relatedSubject", getRelatedSubject());
		body.put("namespaces", namespaces);
		body.put("temporalVisualizationEnabled", getTemporalVisualizationFlag());
		body.put("verbosePropertySwitch", getVerbosePropertyValues());
		
		IndividualTemplateModel itm = getIndividualTemplateModel(individual);
		/* We need to expose non-getters in displaying the individual's property list, 
		 * since it requires calls to methods with parameters.
		 * This is still safe, because we are only putting BaseTemplateModel objects
		 * into the data model: no real data can be modified. 
		 */
		// body.put("individual", wrap(itm, BeansWrapper.EXPOSE_SAFE));
		body.put("individual", wrap(itm, new ReadOnlyBeansWrapper()));
		
		body.put("headContent", getRdfLinkTag(itm));	       
		
		//If special values required for individuals like menu, include values in template values
		body.putAll(getSpecialEditingValues());
		
		String template = new IndividualTemplateLocator(vreq, individual).findTemplate();
		        
		return new TemplateResponseValues(template, body);
	}

	/**
	 * Check if a "relatedSubjectUri" parameter has been supplied, and, if so,
	 * retrieve the related individual.
	 * 
	 * Some individuals make little sense standing alone and should be displayed
	 * in the context of their relationship to another.
	 */
    private Map<String, Object> getRelatedSubject() {
        Map<String, Object> map = null;
        
        String relatedSubjectUri = vreq.getParameter("relatedSubjectUri"); 
        if (relatedSubjectUri != null) {
            Individual relatedSubjectInd = iDao.getIndividualByURI(relatedSubjectUri);
            if (relatedSubjectInd != null) {
                map = new HashMap<String, Object>();
                map.put("name", relatedSubjectInd.getName());

                // TODO find out which of these values is the correct one
                map.put("url", UrlBuilder.getIndividualProfileUrl(relatedSubjectInd, vreq));
                map.put("url", (new ListedIndividual(relatedSubjectInd, vreq)).getProfileUrl());
                
                String relatingPredicateUri = vreq.getParameter("relatingPredicateUri");
                if (relatingPredicateUri != null) {
                    ObjectProperty relatingPredicateProp = opDao.getObjectPropertyByURI(relatingPredicateUri);
                    if (relatingPredicateProp != null) {
                        map.put("relatingPredicateDomainPublic", relatingPredicateProp.getDomainPublic());
                    }
                }
            }
        }
        return map;
    }
    
	private boolean getTemporalVisualizationFlag() {
		String property = ConfigurationProperties.getBean(vreq).getProperty(
				"visualization.temporal");
		return "enabled".equals(property);
	}

    private Map<String, Object> getVerbosePropertyValues() {
        Map<String, Object> map = null;
        
        if (PolicyHelper.isAuthorizedForActions(vreq, SimplePermission.SEE_VERBOSE_PROPERTY_INFORMATION.ACTIONS)) {
            // Get current verbose property display value
            String verbose = vreq.getParameter("verbose");
            Boolean verboseValue;
            // If the form was submitted, get that value
            if (verbose != null) {
                verboseValue = "true".equals(verbose);
            // If form not submitted, get the session value
            } else {
                Boolean verbosePropertyDisplayValueInSession = (Boolean) vreq.getSession().getAttribute("verbosePropertyDisplay"); 
                // True if session value is true, otherwise (session value is false or null) false
                verboseValue = Boolean.TRUE.equals(verbosePropertyDisplayValueInSession);           
            }
            vreq.getSession().setAttribute("verbosePropertyDisplay", verboseValue);
            
            map = new HashMap<String, Object>();
            map.put("currentValue", verboseValue);

            /* Factors contributing to switching from a form to an anchor element:
               - Can't use GET with a query string on the action unless there is no form data, since
                 the form data is appended to the action with a "?", so there can't already be a query string
                 on it.
               - The browser (at least Firefox) does not submit a form that has no form data.
               - Some browsers might strip the query string off the form action of a POST - though 
                 probably they shouldn't, because the HTML spec allows a full URI as a form action.
               - Given these three, the only reliable solution is to dynamically create hidden inputs
                 for the query parameters. 
               - Much simpler is to just create an anchor element. This has the added advantage that the
                 browser doesn't ask to resend the form data when reloading the page.
             */
            String url = vreq.getRequestURI() + "?verbose=" + !verboseValue;
            // Append request query string, except for current verbose value, to url
            String queryString = vreq.getQueryString();
            if (queryString != null) {
                String[] params = queryString.split("&");
                for (String param : params) {
                    if (! param.startsWith("verbose=")) {
                        url += "&" + param;
                    }
                }
            }
            map.put("url", url);            
        } else {
            vreq.getSession().setAttribute("verbosePropertyDisplay", false);
        }
        
        return map;
    }
    
	private IndividualTemplateModel getIndividualTemplateModel(
			Individual individual) {
		//individual.sortForDisplay();
		return new IndividualTemplateModel(individual, vreq);
	}
		
    private TemplateModel wrap(Object obj, BeansWrapper wrapper) throws TemplateModelException {
        return wrapper.wrap(obj);
    }

    private String getRdfLinkTag(IndividualTemplateModel itm) {
        String linkTag = null;
        String linkedDataUrl = itm.getRdfUrl();
        if (linkedDataUrl != null) {
            linkTag = "<link rel=\"alternate\" type=\"application/rdf+xml\" href=\"" +
                          linkedDataUrl + "\" /> ";
        }
        return linkTag;
    }
    
    //Get special values for cases such as Menu Management editing
    private Map<String, Object> getSpecialEditingValues() {
        Map<String, Object> map = new HashMap<String, Object>();
        
    	if(vreq.getAttribute(VitroRequest.SPECIAL_WRITE_MODEL) != null) {
    		map.put("reorderUrl", UrlBuilder.getUrl(DisplayVocabulary.REORDER_MENU_URL));
    	}
    	
    	return map;
    }
}
