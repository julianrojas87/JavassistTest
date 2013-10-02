package org.telcomp.sbb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.expr.FieldAccess;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.slee.ActivityContextInterface;
import javax.slee.RolledBackContext;
import javax.slee.SbbContext;
import javax.slee.serviceactivity.ServiceActivity;
import javax.slee.serviceactivity.ServiceActivityFactory;
import javax.slee.serviceactivity.ServiceStartedEvent;

import org.telcomp.utils.ExpressionEditor;

import de.schlichtherle.truezip.file.TFile;
import de.schlichtherle.truezip.file.TFileInputStream;
import de.schlichtherle.truezip.file.TFileOutputStream;
import de.schlichtherle.truezip.file.TVFS;

public abstract class JavassistSbb implements javax.slee.Sbb {
	
	private ServiceActivityFactory saf;
	private final String deployPath = "/usr/local/Mobicents-JSLEE/jboss-5.1.0.GA/server/default/deploy/";
	private final String tempDirPath = "/usr/local/Mobicents-JSLEE/temp/";
	private final String duJarCmpt = "-DU.jar";
	private final String sbbJarCmpt = "CS-sbb.jar";
	private final String sbbClassCmpt = "CSSbb";
	private final String sbbPath = "org.telcomp.sbb.";
	

	public void onServiceStartedEvent (ServiceStartedEvent  event, ActivityContextInterface aci) {
		ServiceActivity sa = saf.getActivity();
		if(sa.equals(aci.getActivity())){
			try {
				//Name of the Orchestrator service that is going to be reconfigured
				//It should be provided as a parameter to this process
				String serviceName = "LinkedInJobNotificator";
				//Unjar the DU of the orchestrator service and save its Sbb jar file in a temp directory
				String newTempDir = this.getSbbJar(serviceName);
				
				ClassPool cp = ClassPool.getDefault();
				//Inserting required Class definitions to ClassPool to modify orchestrator service Class
				cp.insertClassPath(newTempDir + serviceName + sbbJarCmpt);
				//JAIN SLEE library included to avoid compilation errors
				cp.insertClassPath(deployPath + "mobicents-slee/lib/jain-slee-1.1.jar");
				//For this example, including EndGetData Event Class because its handling method is the one to be modified
				cp.insertClassPath("/home/julian/Telcomp-Workspace/DataAccessService/jars/EndGetData-event.jar");
				cp.insertClassPath("/home/julian/Telcomp-Workspace/WebServiceInvocator/jars/EndWSInvocator-event.jar");
				//Getting orchestrator service class instance
				CtClass ctclass = cp.get(sbbPath + serviceName + sbbClassCmpt);
				
				//For this example, adding a new WS input parameter because the new reconfiguration WS has
				//an additional input compared to the old reconfigurated WS
				CtField ctf = CtField.make("static java.lang.String ws0ipn1;", ctclass);
				CtField ctf1 = CtField.make("static java.lang.String ws0ipv1;", ctclass);
				ctclass.addField(ctf);
				ctclass.addField(ctf1);
				
				//For this example, setting all parameters for the new reconfiguration WS on the setSbbContext method 
				//including wsdl, operation name and I/O parameter names
				CtMethod method = ctclass.getDeclaredMethod("setSbbContext");
				method.insertAfter("{ws0wsdl = \"http://www.webservicex.net/globalweather.asmx?wsdl\";" +
						"ws0operation = \"GetWeather\"; ws0ipn0 = \"CityName\"; ws0ipn1 = \"CountryName\";" +
						"ws0opn0 = \"GetWeatherResult\";}");
				
				//For this example, getting the method where the old reconfigurated WS is invoked to set the previously added
				//new input parameter value and include it to the invocation HashMap
				CtMethod method1 = ctclass.getDeclaredMethod("onEndGetDataEvent");
				
				//Using the defined org.telcomp.utils.ExpressionEditor class which extends from javassist.expr.ExprEditor
				//to edit the method to avoid ClassNotFoundException for the anonymous inner class definition
				method1.instrument(new ExpressionEditor(){
					public void edit(FieldAccess f) throws CannotCompileException{
						if(f.getFieldName().equals("ws0operationInputs")){
							f.replace("{ws0ipv0 = \"Bogota\"; ws0ipv1 = \"Colombia\"; " +
									"$_ = $proceed($$); ws0operationInputs.put(ws0ipn1, ws0ipv1);}");
						}
					}
				});
				
				CtMethod method2 = ctclass.getDeclaredMethod("onEndWSInvocatorEvent");
				method2.insertBefore("{System.out.println(\"Sucess: \" + $1.isSuccess());}");
				//Write the reconfigurated SBB Class file in the temporal directory
				ctclass.writeFile(newTempDir);
				//Defrost so it can be modified again and Detach so it's unloaded from ClassPool
				ctclass.defrost();
				ctclass.detach();
				
				//Getting the path of the reconfigurated SBB Class file
				String newClassFilePath = newTempDir + sbbPath.replace(".", "/") + serviceName + sbbClassCmpt + ".class";
				//Copying the reconfigurated SBB Class file into its corresponding Deloyable Unit
				this.updateDUJar(serviceName, newClassFilePath);
				//Deleting all temporal files created during the reconfiguration
				this.deleteTemporals(newTempDir);
			} catch(Exception e){
				e.printStackTrace();
			}
		}
	}
	
	private String getSbbJar(String serviceName){
		JarFile jar;
		int files = new File(tempDirPath).list().length;
		String newTempDir = null;
		try {
			newTempDir = this.createNewTempDir(files);
			jar = new JarFile(deployPath + this.getDuName(serviceName) + duJarCmpt);
			JarEntry entry = (JarEntry) jar.getEntry("jars/" + serviceName + sbbJarCmpt);
			File f = new File(newTempDir + serviceName + sbbJarCmpt);
			InputStream is = jar.getInputStream(entry);
			FileOutputStream fos = new FileOutputStream(f);
	        while (is.available() > 0) {
	            fos.write(is.read());
	        }
	        fos.close();
	        is.close();
	        jar.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return newTempDir;
	}
	
	private void updateDUJar(String serviceName, String newClassFile){
		TFile tFileRead = new TFile(newClassFile);
		TFile tFileWrite = new TFile(deployPath + this.getDuName(serviceName) + duJarCmpt + "/jars/" + 
				serviceName + sbbJarCmpt + "/" + sbbPath.replace(".", "/") + serviceName + sbbClassCmpt + ".class");
		try {
			TFileInputStream tfIs = new TFileInputStream(tFileRead);
			TFileOutputStream tfOs = new TFileOutputStream(tFileWrite);
			while(tfIs.available() > 0){
				tfOs.write(tfIs.read());
			}
			tfIs.close();
			tfOs.close();
			TVFS.umount();
		} catch (Exception e) {
			e.printStackTrace();
		} 
	}
	
	private void deleteTemporals(String directory){
		String temporal = directory.substring(0, directory.length()-1);
		TFile temp = new TFile(temporal);
		try {
			temp.rm_r();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private String createNewTempDir(int files){
		try {
			Runtime run = Runtime.getRuntime();
			Process p = run.exec("mkdir "+tempDirPath+files);
			p.waitFor();
		    p.destroy();
		} catch(Exception e){
			e.printStackTrace();
		}
		return tempDirPath + files + "/";
	}
	
	private String getDuName(String serviceName){
		String duTemp = serviceName.substring(1);
		return serviceName.substring(0, 1).toLowerCase().concat(duTemp);
	}
	
	
	// TODO: Perform further operations if required in these methods.
	public void setSbbContext(SbbContext context) { 
		this.sbbContext = context;
		try{
			Context ctx = (Context) new InitialContext().lookup("java:comp/env"); 
			saf = (ServiceActivityFactory) ctx.lookup("slee/serviceactivity/factory"); 
		} catch (NamingException e){
			e.printStackTrace();
		}
	}
    public void unsetSbbContext() { this.sbbContext = null; }
    
    // TODO: Implement the lifecycle methods if required
    public void sbbCreate() throws javax.slee.CreateException {}
    public void sbbPostCreate() throws javax.slee.CreateException {}
    public void sbbActivate() {}
    public void sbbPassivate() {}
    public void sbbRemove() {}
    public void sbbLoad() {}
    public void sbbStore() {}
    public void sbbExceptionThrown(Exception exception, Object event, ActivityContextInterface activity) {}
    public void sbbRolledBack(RolledBackContext context) {}
	

	
	/**
	 * Convenience method to retrieve the SbbContext object stored in setSbbContext.
	 * 
	 * TODO: If your SBB doesn't require the SbbContext object you may remove this 
	 * method, the sbbContext variable and the variable assignment in setSbbContext().
	 *
	 * @return this SBB's SbbContext object
	 */
	
	protected SbbContext getSbbContext() {
		return sbbContext;
	}

	private SbbContext sbbContext; // This SBB's SbbContext

}
