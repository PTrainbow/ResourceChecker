# ResourceChecker

简单的资源去重插件

提供原理和思路，可以 fork 自己添加额外白名单配置等

## 功能

1. 具备资源去重的基本功能
2. 适配 AGP 7.x
3. 适配 split abi

## 原理

为 ResourcesTask 注入 last action，优先寻找 OptimizeResourcesTask(AGP 7.x 默认存在，但是也可以强制关闭此 task)，其次寻找 ProcessResourcesTask

action 主要逻辑为：
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

## 有趣的发现

测试 demo 的时候发现一个有趣的现象，release 打出来的包，经过插件处理以后，安装时的 logo 颜色不对

后来发现因为我的机器是 macOS，大小写不敏感，在处理 .ap_ 文件时，解压出来的文件存在`忽略大小写`后名称一样的文件，导致资源文件被覆盖

导致资源出现了问题

所以，如果我们有自定义的资源的解压操作存在，不可以在 macOS 上进行打包

