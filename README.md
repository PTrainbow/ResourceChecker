# ResourceChecker

资源去重插件

## 功能

1. 具备资源去重的基本功能
2. 适配 AGP 7.x
3. 适配 split abi

## 原理
为 OptimizeResourcesTask(注意：release type 才可能有此任务) 注入 last action

主要逻辑为：
1. 拿到资源打包后的 .ap_ 文件，读取 .ap_ 文件，获取到资源文件的 crc 码，收集重复的资源文件

2. 修改 .arsc 文件，使不同的资源名指向相同的原始资源文件，并删除重复的资源文件

3. 生成新的 .arsc 文件，打包新的 .ap_ 文件

## 使用方式

因为没有上传到 mavenCentral，所以需要自己手动 publishToMavenLocal 一下

然后在根目录 build.gradle 取消注释

```groovy
// classpath "com.ptrain.android:resourcechecker:0.0.1"
```
app 目录 build.gradle 取消注释

```groovy
// apply plugin: "com.ptrain.android.resourcechecker"
```

## 验证

我们在 MainActivity 的布局文件中引入了两个相同的、文件名不同的图片，account 和 account1

最终打出的 release apk 中，account 和 account1 指向同一份资源，冗余资源被剔除

![](/doc/arsc_table_duplicated.png)