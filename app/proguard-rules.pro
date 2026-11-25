# 0.6.41 重建版补充的 R8 规则（原 proguard-rules.pro 未随源码包保留，此文件为重建）
# 原则：只保留「按名字反射/序列化访问」的东西，其余交给 R8 自己收缩。

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room：实体/DAO 由 KSP 生成实现类，按字段名与构造器访问，混淆后容易出现
# "Cannot find setter/field" 类运行时问题，整包 keep 最省事（体积影响可忽略）
-keep class com.am.music.data.db.** { *; }
-keepclassmembers class com.am.music.data.db.** { *; }

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 反射探测（de.robv.android.xposed.*）只 forName 外部类，目标不在包内，
# 不需要 keep；Kotlin 元数据不需要保留（无 kotlin-reflect 使用）
