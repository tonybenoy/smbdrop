# SMB stack (reflection, ServiceLoader, JCA providers)
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.google.common.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keep class asn1.** { *; }
-keep class com.hierynomus.asn1.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
# Room entities
-keep class com.tonybenoy.smbdrop.data.** { *; }
# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn java.rmi.MarshalException
-dontwarn java.rmi.UnmarshalException