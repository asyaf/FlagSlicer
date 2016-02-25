package example;

public class Test {
	
	public static boolean foo(int cd, boolean flag) {
		int de = 4;
		System.out.println(cd + de);
		int a;
		a = 1;
		int b = 0;
		if(flag) {
			b = a * 2 + cd;
			int c = a + b;
			System.out.println("flag is true " + c);
		} else {
			System.out.println("flag is false");
		}
		
		if(flag) {
			for(int i = 0; i < 10; ++i) {
				System.out.println(i);
			}
		}
		
		b *= 5;
		System.out.println("hello");
		System.out.println(b);
		return true;
	}
	
	public static boolean getFlag(boolean flag) {
		return flag;
	}
	
	public static void main(String args[]) {
		boolean a = getFlag(true);
		foo(3, false);
	}
}
