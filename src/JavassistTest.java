
public class JavassistTest {
	
	public static String wsdl0 = "wsdl0";
	public static String wsdl1 = "wsld1";
	public static String operation0 = "operation0";
	public static String operation1 = "operation1";
	
	
	public void onEndWSInvocatorEvent(){
		System.out.println("Javassist Test, value of wsdl1: "+wsdl1);
		operation1 = "whatever";
	}


	public static void main(String[] args) {
		
		JavassistTest newClass = new JavassistTest();
		newClass.onEndWSInvocatorEvent();
	}

}
