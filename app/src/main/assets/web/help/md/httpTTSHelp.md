# 在线朗读规则说明

* 在线朗读规则为url规则,同书源url

## js参数

```
speakText //朗读文本
speakSpeed //朗读速度,5-50
```

## 例

```
http://tts.baidu.com/text2audio,{
    "method": "POST",
    "body": "tex={{java.encodeURI(java.encodeURI(speakText))}}&spd={{String((speakSpeed + 5) / 10 + 4)}}&per=5003&cuid=baidu_speech_demo&idx=1&cod=2&lan=zh&ctp=1&pdt=1&vol=5&pit=5&_res_tag_=audio"
}
```

## 字段说明

* url: 请求规则,支持method/body/headers等url选项,同书源
* Content-Type: 服务端音频MIME,如audio/mpeg
* 并发率: 请求间隔,同书源concurrentRate
* 登录url/登录UI/登录检测js: 登录协议,同书源
* 请求头: 全局请求头JSON
* jsLib: 共享给本引擎所有JS(含登录检测js)调用的公共JS库文本;函数内需通过this取java/source等对象
* 启用CookieJar: 请求自动保存/携带Cookie,登录或session接口需开启
* 段落间隔: 段落间插入静音毫秒数
