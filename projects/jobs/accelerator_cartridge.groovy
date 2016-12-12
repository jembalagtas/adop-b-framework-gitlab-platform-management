// Constants
def gerritBaseUrl = "ssh://jenkins@gerrit:29418"
def cartridgeBaseUrl = gerritBaseUrl + "/cartridges"
def platformToolsGitUrl = gerritBaseUrl + "/platform-management"

//Configure
Closure passwordParam(String paramName, String paramDescription, String paramDefaultValue) {
    return { project ->
        project / 'properties' / 'hudson.model.ParametersDefinitionProperty' / 'parameterDefinitions' << 'hudson.model.PasswordParameterDefinition' {
            'name'(paramName)
            'description'(paramDescription)
            'defaultValue'(paramDefaultValue)
        }
    }
}

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"

def projectFolderName = workspaceFolderName + "/${PROJECT_NAME}"
def projectFolder = folder(projectFolderName)

def cartridgeManagementFolderName= projectFolderName + "/Cartridge_Management"
def cartridgeManagementFolder = folder(cartridgeManagementFolderName) { displayName('Cartridge Management') }

// Cartridge List
//def cartridge_list = []
//readFileFromWorkspace("${WORKSPACE}/cartridges.txt").eachLine { line ->
//    cartridge_repo_name = line.tokenize("/").last()
//    local_cartridge_url = cartridgeBaseUrl + "/" + cartridge_repo_name
//    cartridge_list << local_cartridge_url
//}


// Jobs
def loadCartridgeJob = freeStyleJob(cartridgeManagementFolderName + "/Load_EBS_Accelerator_Cartridge")

// Setup Load_Cartridge
loadCartridgeJob.with{
    parameters{
        stringParam('CARTRIDGE_CLONE_URL', 'ssh://git@newsource.accenture.com/adopebs/adop-b-framework-oracle-ebs-cartridge-211.git', 'The ADOP cartridge url that contains the groovy scripts to create the jenkins jobs. Add jenkins ssh key to your innersource account to clone this.')
        stringParam('ENVIRONMENT', '','Your environment name. It is important that this name is also aligned with your SVN branch.')
        stringParam('SCM_PROJECT_URL', 'http://svn-server/svn/R12EBS', 'By default, this http://svn-server/svn resolves to the dockerized Collabnet svn server. If you have an external SVN server change this url accordingly.')
        stringParam('SCM_USER', 'admin', 'Your private SVN server administrator')
        stringParam('APP_SSH_USER', 'applmgr', 'The unix user that has access to your application server.')
        stringParam('APP_SERVER', '', 'The resolvable hostname or IP address of your application server.')
        stringParam('APPL_HOME', '', '  Oracle EBS application specific value. Consult with your Apps DBAs.')
        stringParam('XXCU_TOP_DIRECTORY', '', 'Oracle EBS application specific value. Consult with your Apps DBAs.')
        stringParam('COMMON_TOP', '', 'Oracle EBS application specific value. Consult with your Apps DBAs.')
        stringParam('FORMS_TRACE_DIR', '', 'Oracle EBS application specific value. Consult with your Apps DBAs.')
        stringParam('ERP_MANAGER_DB_HOST', 'ricewmanager.local', 'By default, ricewmanager.local resolves to the ERP postgres container.')
        stringParam('ERP_MANAGER_DB_PORT', '5432', 'Do not change if you are not using an external ERP manager database.')
        stringParam('ERP_MANAGER_DB_PASSWORD', 'welcome1', 'By default, welcome1 is the database password for acn_erp_manager.')
        stringParam('DB_READ_USER', 'readall', 'The database user from the target Oracle Database environment that has a read access.')
        stringParam('DB_SERVER', '', 'The resolvable hostname or IP address of your Oracle Database server.')
        stringParam('DB_NAME', '', 'Your Oracle Database SID.')
        stringParam('DB_PORT', '1521', 'The target Oracle Database server port.')
        booleanParam('IMPORT_REFERENCE_PROJECT', false, 'Uncheck this if you already have imported a reference project.')
        stringParam('PROJECT_CLONE_SVN_URL', 'https://10.9.238.88:18080/svn/R12EBS/branches/DEVELOPMENT', 'The default value is from the Accenture Engineered Systems Lab and is accessible from vpn.accenture.com. The downloaded codes will be committed to the Private Svn repository.')
        stringParam('PROJECT_CLONE_SVN_USER' 'admin', '')
    }
    configure passwordParam('SCM_PASSWORD', 'Your private SVN server administrator password.', 'admin')
    configure passwordParam('PROJECT_CLONE_SVN_PASSWORD', '', '')

    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROECT_NAME',projectFoldeJrName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
	configure { project ->
		project / 'buildWrappers' / 'org.jenkinsci.plugins.credentialsbinding.impl.SecretBuildWrapper' / 'bindings' / 'org.jenkinsci.plugins.credentialsbinding.impl.StringBinding' {
		    'credentialsId'('gitlab-secrets-id')
			'variable'('GITLAB_TOKEN')
		}
	}
    steps {
        shell('''#!/bin/bash
if [ $IMPORT_REFERENCE_PROJECT == true ]; then
  svn co ${PROJECT_CLONE_SVN_URL} R12EBS --username=${PROJECT_CLONE_SVN_USER} --password=${PROJECT_CLONE_SVN_PASSWORD} --non-interactive --no-auth-cache --trust-server-cert

  svn import -m "New import" R12EBS ${SCM_PROJECT_URL} --username=admin --password=admin --non-interactive --no-auth-cache --trust-server-cert           
else
 echo "No projects were imported.."
fi
  
        ''')
    }
    steps {
        shell('''#!/bin/bash -ex
chmod +x ${WORKSPACE}/common/gitlab/create_project.sh

# We trust everywhere
#echo -e "#!/bin/sh 
#exec ssh -o StrictHostKeyChecking=no "\$@" 
#" > ${WORKSPACE}/custom_ssh
#chmod +x ${WORKSPACE}/custom_ssh
#export GIT_SSH="${WORKSPACE}/custom_ssh"        
        
# Clone Cartridge
git clone ${CARTRIDGE_CLONE_URL} cartridge

repo_namespace="${PROJECT_NAME}"
permissions_repo="${repo_namespace}/permissions"

# Create repositories
mkdir ${WORKSPACE}/tmp
cd ${WORKSPACE}/tmp
while read repo_url; do
    if [ ! -z "${repo_url}" ]; then
        repo_name=$(echo "${repo_url}" | rev | cut -d'/' -f1 | rev | sed 's#.git$##g')
        target_repo_name="${WORKSPACE_NAME}/${repo_name}"
        
        # get the namespace id of the group
		gid="$(curl --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "http://gitlab/gitlab/api/v3/groups/${WORKSPACE_NAME}" | python -c "import json,sys;obj=json.load(sys.stdin);print obj['id'];")"
				
		# create new project				
		${WORKSPACE}/common/gitlab/create_project.sh -g http://gitlab/gitlab/ -t "${GITLAB_TOKEN}" -w "${gid}" -p "${repo_name}"	
        
        # Populate repository
        git clone git@gitlab:"${target_repo_name}.git"
        cd "${repo_name}"
        git remote add source "${repo_url}"
        git fetch source
        git push origin +refs/remotes/source/*:refs/heads/*
        cd -
    fi
done < ${WORKSPACE}/cartridge/src/urls.txt

# Provision one-time infrastructure
if [ -d ${WORKSPACE}/cartridge/infra ]; then
    cd ${WORKSPACE}/cartridge/infra
    if [ -f provision.sh ]; then
        source provision.sh
    else
        echo "INFO: cartridge/infra/provision.sh not found"
    fi
fi

# Generate Jenkins Jobs
if [ -d ${WORKSPACE}/cartridge/jenkins/jobs ]; then
    cd ${WORKSPACE}/cartridge/jenkins/jobs
    if [ -f generate.sh ]; then
        source generate.sh
    else
        echo "INFO: cartridge/jenkins/jobs/generate.sh not found"
    fi
fi
''')
        systemGroovyCommand('''
import jenkins.model.*
import groovy.io.FileType

def jenkinsInstace = Jenkins.instance
def projectName = build.getEnvironment(listener).get('PROJECT_NAME')
def xmlDir = new File(build.getWorkspace().toString() + "/cartridge/jenkins/jobs/xml")
def fileList = []

xmlDir.eachFileRecurse (FileType.FILES) { file ->
    if(file.name.endsWith('.xml')) {
        fileList << file
    }
}
fileList.each {
	String configPath = it.path
  	File configFile = new File(configPath)
    String configXml = configFile.text
    ByteArrayInputStream xmlStream = new ByteArrayInputStream( configXml.getBytes() )
    String jobName = configFile.getName().substring(0, configFile.getName().lastIndexOf('.'))

    jenkinsInstace.getItem(projectName,jenkinsInstace).createProjectFromXML(jobName, xmlStream)
}
''')
        dsl {
            external("cartridge/jenkins/jobs/dsl-ricew/**/*.groovy")
        }

    }
    scm {
        git {
            remote {
                name("origin")
                url("git@gitlab:root/platform-management.git")
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
}