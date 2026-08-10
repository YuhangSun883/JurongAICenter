package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NewAPI 中转站客户端
 * 用于主动查询视频任务状态并下载产物
 *
 * 背景：ComfyUI 节点的 JurongImageToVideo 在 save_video_file 阶段偶发失败，
 * 导致 outputs 为空，Spring Boot 端拿不到 video_path。
 * 此接口允许手动补救：传入 NewAPI task_id → 查状态 → 拿 URL → 下载上传到 MinIO。
 */
@Slf4j
@Component
public class NewApiClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${newapi.base-url}")
    private String baseUrl;

    @Value("${newapi.token}")
    private String token;

    @Value("${newapi.vision-model:qwen-vl-max}")
    private String visionModel;

    public NewApiClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 查询 NewAPI 视频任务状态
     * @param taskId NewAPI 返回的 task_id
     * @return NewAPI 响应 JSON（含 status / metadata.url 等字段）
     */
    public JsonNode pollVideo(String taskId) {
        try {
            return webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/v1/videos/{taskId}", taskId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/videos/{} failed: {} {}",
                        taskId, e.getStatusCode(), e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "NewAPI query failed: " + e.getMessage());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI /v1/videos/{} error: {}", taskId, e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI pollVideo({}) failed: {}", taskId, e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    /**
     * 同步等待 NewAPI 视频任务完成
     * @param taskId NewAPI task_id
     * @param timeoutSec 最大等待秒数
     * @return 最终状态（completed 时含 metadata.url）
     */
    public JsonNode waitForVideo(String taskId, int timeoutSec) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        int pollInterval = 5;  // 秒
        String lastStatus = "";

        while (System.currentTimeMillis() - start < timeoutMs) {
            JsonNode result = pollVideo(taskId);
            String status = result != null && result.has("status") ?
                result.get("status").asText("unknown") : "unknown";

            if (!status.equals(lastStatus)) {
                log.info("NewAPI video task {} status: {}", taskId, status);
                lastStatus = status;
            }

            if ("completed".equalsIgnoreCase(status)
                || "succeeded".equalsIgnoreCase(status)
                || "success".equalsIgnoreCase(status)) {
                return result;
            }
            if ("failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
                throw new BusinessException(ErrorCode.NEWAPI_TASK_FAILED,
                    "NewAPI 视频任务失败: " + result.toString());
            }

            try {
                Thread.sleep(pollInterval * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "轮询被中断");
            }
        }
        throw new BusinessException(ErrorCode.NEWAPI_TASK_TIMEOUT,
            "NewAPI 视频任务超时 (" + timeoutSec + "s): " + taskId);
    }

    /**
     * 调用 NewAPI 的 Chat Completions（用于画布文本润色 / Agent 对话等）
     *
     * @param model        模型名（如 "deepseek-v4-flash"）
     * @param systemPrompt 系统提示词（可空）
     * @param userPrompt   用户输入
     * @param maxTokens    最大输出 token 数
     * @return 模型返回的文本内容
     */
    public String chatCompletion(String model, String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.7);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // LLM 润色在 aicoming 队列里可能会等很久,60s 太少
                // 生产中会出现 "Did not observe any item within 60000ms" 导致文本节点失败
                .timeout(Duration.ofSeconds(180))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/chat/completions failed: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "LLM 调用失败: " + e.getStatusCode());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI chat error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null || !response.has("choices") || response.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM 返回为空");
            }
            JsonNode first = response.get("choices").get(0);
            JsonNode msg = first.get("message");
            if (msg == null || !msg.has("content")) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM 返回无 content");
            }
            String content = msg.get("content").asText();
            log.info("LLM chat OK: model={}, inputLen={}, outputLen={}",
                model, userPrompt.length(), content.length());
            return content;
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI chatCompletion failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    /**
     * 多模态 chat completion（支持图片理解）。
     *
     * <p>调用 /v1/chat/completions，传 user message 时使用 OpenAI 多模态格式：
     * <pre>
     * content: [
     *   {type:"text", text:"..."},
     *   {type:"image_url", image_url:{url:"https://..."}}
     * ]
     * </pre>
     *
     * <p>如果 NewAPI 中转服务器访问不到公网 URL（实测 claude-sonnet-4-6 这种情况），
     * 本方法会自动下载图片 → 转 Base64 data URI 后再传，避免中转服务器去访问外网。
     *
     * @param model        多模态模型（如 claude-sonnet-4-6）
     * @param systemPrompt 系统 prompt
     * @param userText     用户文本（不含图片）
     * @param imageUrls    公网图片 URL 列表（可以是 MinIO/OSS 内部地址，会被本服务下载）
     * @param maxTokens    最大输出 token
     * @return LLM 回复内容
     */
    public String chatCompletionWithImages(String model, String systemPrompt, String userText,
                                            List<String> imageUrls, int maxTokens) {
        // 组装 user content: [text, image_url, image_url, ...]
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userText == null ? "" : userText));
        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (url == null || url.isBlank()) continue;
                String finalUrl = url;
                try {
                    // 尝试下载 + 转 base64（应对 NewAPI 中转服务器无法访问公网 URL 的情况）
                    finalUrl = downloadAsDataUri(url);
                } catch (Exception e) {
                    log.warn("[chatCompletionWithImages] failed to download image as base64, fallback to URL: url={}, err={}",
                        url, e.getMessage());
                    // 下载失败就退回到直接传 URL（部分模型/中转可能能访问）
                }
                userContent.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", finalUrl)
                ));
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userContent));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.7);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(180))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI multimodal chat failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "LLM multimodal failed: " + e.getStatusCode());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI multimodal error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null || !response.has("choices") || response.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM multimodal response is empty");
            }
            JsonNode first = response.get("choices").get(0);
            JsonNode msg = first.get("message");
            if (msg == null || !msg.has("content")) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM multimodal content missing");
            }
            String content = msg.get("content").asText();
            log.info("LLM multimodal OK: model={}, images={}, inputTextLen={}, outputLen={}",
                model, imageUrls == null ? 0 : imageUrls.size(), userText == null ? 0 : userText.length(), content.length());
            return content;
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI chatCompletionWithImages failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    // downloadAsDataUri 已在文件下方定义（line 1019 附近），直接复用。

    /**
     * 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鎯у⒔閹虫捇鈥旈崘顏佸亾閿濆簼绨奸柟鐧哥秮閺岋綁顢橀悙鎼闂傚洤顦甸弻銊モ攽閸♀晜效婵炲瓨鍤庨崐婵嬪蓟閵堝绾ч柟绋块娴犳挳鎮楀▓鍨灈闁绘牜鍘ч悾鐑芥偂鎼搭喗鍍靛銈嗘尵閸犳捁銇愰崨瀛樷拻濞达綀顫夐崑鐘绘煕閺傝法肖闁瑰箍鍨藉畷姗€顢欓崲澶涚畵閺屾盯寮撮妸銉т哗闂佸憡鍔忛崑鎾翠繆閻愵亜鈧牠宕濋敃鈧…鍧楀焵椤掑倻纾兼い鏃傚帶椤ｅ磭绱掓潏銊﹀鞍闁瑰嘲鎳橀獮鎾诲箳瀹ュ拋妫滃┑鐘垫暩婵即宕规總绋挎槬闁哄稁鍘肩粈澶愭煛瀹ュ骸骞楅柛瀣€圭换娑㈠幢濡闉嶉梺缁樻尵閸犳牠寮婚悢琛″亾閻㈠憡娅滅紒杈珪閵囧嫯绠涢幘璺侯杸闂佺锕ゅ锟犲蓟閿濆绠涢梻鍫熺☉椤偆绱?NewAPI 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳婀遍埀顒傛嚀鐎氼參宕崇壕瀣ㄤ汗闁圭儤鍨归崐鐐烘偡濠婂啰绠荤€殿喗濞婇弫鍐磼濞戞艾骞楅梻渚€娼х换鍫ュ春閸曨垱鍊块柛鎾楀懐锛滈梺褰掑亰閸欏骸鈻撳鍫熺厸鐎光偓閳ь剟宕伴弽顓犲祦鐎广儱顦介弫濠勭棯閹峰矂鍝烘慨锝咁樀濮婄粯鎷呮笟顖滃姼濡炪倖鍨堕崹褰掑箲閵忕姭鏀介悗锝庝海閹芥洟姊洪崫鍕窛闁哥姴娴峰▎銏ゆ倷閻戞鍘卞銈嗗姧缁茶法绮婚幘鎰佺唵閻熸瑥瀚悡銉╂煃鐟欏嫬鐏撮柟顔界懇瀵爼骞嬮悩杈敇闂備浇澹堥敓銉╁磹濠靛钃熼柨鐔哄Т閻掑灚銇勯幒宥堝厡妞も晝鍏橀幃妤呮晲鎼粹€茬盎闂侀€炲苯澧伴柡浣割煼瀵濡搁妷銏℃杸闂佺硶鍓濋〃鍡椻枔椤撶喓绡€缁炬澘顦辩壕鍧楁煕韫囨棑鑰跨€殿喛顕ч埥澶婎潩閿濆懍澹曢梺鎸庣箓妤犲憡绂嶅┑鍫氬亾鐟欏嫭绀€闁活剙銈搁崺鈧い鎺戝枤濞兼劖绻涢崣澶樼劷闁轰緡鍣ｉ弫鎾绘偐閼碱剛鏆繝鐢靛仜濡瑩骞愰幖浣瑰珔闁绘柨鍚嬮悡蹇擃熆鐠鸿櫣澧曢柛鏃€绮撻弻锟犲幢閳衡偓闁垱鎱ㄦ繝鍌ょ吋鐎规洘甯掗埢搴ㄥ箣椤撶啘婊堟⒒娴ｅ憡璐￠柍宄扮墦瀹曟垿宕熼鐐茬柧闂傚倷绶氬褑澧濋梺鍝勬噺缁嬫挾鍒掗敐鍛傛棃宕ㄩ鐙€鍟囨繝鐢靛剳缂嶅棝宕滃▎鎾村€舵い鏂款潟娴滄粓鏌嶉崫鍕櫤闁轰浇浜槐鎺旂磼濡皷妲堝銈嗘煥缁绘﹢銆佸▎鎾村殥闁靛牆鎳庡В鍫ユ⒒閸屾瑧鍔嶅┑鐐诧躬瀵劑鏌嗗鍛€梺闈╁瘜閸樹粙锝為弴銏＄厵闁诡垎鍛喖婵犳鍨遍幐鎶藉蓟閵堝悿鍦偓锝庝簻閳峰姊洪幖鐐插婵炲皷鈧磭鏆﹂柛婵嗗濡插牓鏌曡箛鏇炐ユい鏂匡躬濮婅櫣鎲撮崟顐㈠Б閻熸粍婢橀ˇ閬嶆嚍鏉堚晜瀚氱€瑰壊鍠楃€靛矂姊洪棃娑氬婵☆偅鐟ф禍鎼佹偨閸涘﹦鍘遍柣搴秵閸撴瑩寮搁幋锔界厸闁告侗鍘鹃崺锝夋煛娴ｇ鏆ｇ€规洘甯掗埥澶婎潩椤掆偓缁犮儱鈹戦敍鍕杭闁稿﹥鐗曢～蹇氥亹閹烘垵鍤戞繝鐢靛У绾板秹宕甸埀顒勬煟閻樺厖鑸柛鏂款樀瀹曟垿骞橀懜闈涙瀭闂佸憡娲﹂崜娑⑺囬妷鈺傗拺缂備焦顭囨晶顏堟煏閸喐鍊愮€规洝顫夐妶锝夊礃閵娿儱鏁ゆ俊鐐€栭幐楣冨磻閻旂顕遍悗锝庡枟閸婄敻鎮峰▎蹇擃仾缂佲偓閳ь剙鈹戦悙鑼勾闁告柨绉归妴鍐ㄢ枎閹剧补鎷婚梺绋挎湰閻燂妇绮婇悧鍫涗簻闁哄洨鍠撴晶鐢碘偓瑙勬磻閸楁娊鐛幒鎳虫棃鍩€椤掑嫭鍋柣鎰靛墰缁♀偓婵犵數濮撮崐褰掑闯閻ｅ瞼纾奸柣妯挎珪椤ュ牓鏌″畝瀣М鐎殿噮鍣ｅ畷鎺懳旀担鍝ュ降闂傚倷娴囧銊х矆娴ｈ櫣鐭撻柣銏㈩焾缁犵偤鏌曟繛鍨姶婵炵鍔戦弻娑㈠焺閸愮偓鐣堕柣搴㈠喕缂嶄礁顫忓ú顏勪紶闁靛鍎扮划鍫曟倵濞堝灝鏋涘褍閰ｉ獮鎴﹀閻橆偅鏂€闁诲函缍嗛崑鍕濞差亝鈷掗柛灞炬皑婢ф盯鏌涢幒鍡椾壕闂備焦瀵х换鍌毼涙惔銏㈩洸婵犲﹤鐗婇悡娑㈡煕閹板墎鍒板ù婊堢畺濮婅櫣绱掑鍡樼暭婵犳鍠楅幐鎶藉箖妤ｅ啯鍊婚柦妯侯槺妤犲洤鈹戦悙鍙夘棤闁稿鎹囧畷鎾绘偨閸涘﹦鍘介梺缁橆焾瀹曠數妲愰悧鍫㈢闁告粌鍟扮粔顕€鏌熼璇插祮濠碉紕鍏橀崺锟犲磼濡や礁顏归梻鍌欑閹诧紕鎹㈤崒婧惧亾濮樼厧娅嶉柛鈹垮灪瀵板嫰骞囬鐘插箞婵犵妲呴崹杈ㄧ娴犲纾婚柕蹇婃噰閸嬫挸鈻撻崹顔界亪闂佺顕滅换婵嬬嵁韫囨稑宸濇い鏍ㄧ☉娴犳帡姊洪悙钘夊姎闁告ɑ鐗楃粩鐔煎即閻愬秵妫冮幃鈺呮濞戞鎹曢梻浣筋嚙缁绘垹鎹㈤崼婵堟殾婵犻潧妫鈺傘亜閹惧鐭嗙紒銊ヮ煼濮婃椽骞愭惔銏╂⒖濡炪倖娉﹂崶銊モ偓鍫曟煥閺囩偛鈧綊鎮″☉銏″€堕柣鎰暩閹藉倿鏌涙惔銏♀拻闁逞屽墲椤煤閺嶎厽鍋夐柛蹇涙？缁诲棙鎱ㄥ┑鍡欑劸婵℃煡绠栧鐑樺濞嗘垹鏆犻梺缁橆殕閹告悂锝炶箛鏃傜瘈婵﹩鍓涢敍婊冣攽椤旂煫顏勭暦椤掑娂鐑藉焵椤掑倻纾介柛灞剧懅椤︼附銇勯幋婵囶棤闁轰緡鍣ｉ獮鎺懳旀繝鍐╂珦闂傚鍋勫ú锕傚箰閹绢喖缁╁ù鐘差儐閻撴洟鏌曟径娑氬埌闁告梹鐟х槐鎺楀Ω閵夘喚鍚嬪┑顔硷攻濡炶棄鐣峰鈧畷锝嗗緞鐎ｎ亜澹嶉梻鍌欑劍鐎笛兠鸿箛娑樼９闁哄洢鍩勯弫瀣煥濠靛棭妯堥柡浣革躬閺屻倝骞侀幒鎴濆閻庢鍠楃€笛呮崲濠靛鍋ㄩ梻鍫熷垁閵忋倖鐓曞┑鐘插€荤粔鐑橆殽閻愬弶顥㈢€规洖銈告俊鐑藉Ψ閿旈敮鍋撻鍕拺闁告挻褰冩禍鏍煕鎼淬垻鍙€闁诡噯绻濆鎾閿涘嫬甯惧┑鐘灱濞夋盯顢栭崨瀛樺€堕柕澶嗘櫆閻撴瑩鏌涢幇顖氱毢閼叉牕鈹戦垾鍐茬骇闁告梹鐟ラ锝夊箻椤旂⒈娼婇梺鎶芥暜閸嬫捇鏌熸搴ｅ笡缂佺粯绋掑蹇涘礈瑜嶉崺宀勬⒑绾拋鍤嬬紒缁樼箞閻涱喗寰勫畝鈧惌娆撳箹鐎涙ɑ灏伴柡鍌楀亾濠碉紕鍋戦崐鏍哄澶婄；闁规儳顕粻鎯归敐鍛毐闁瑰啿楠搁锝夊蓟閵夛腹鎷绘繛杈剧秬濞咃絿鏁☉銏＄厸閻忕偠顕ф慨鍌溾偓瑙勬磸閸庢娊鍩€椤掑﹦绉甸柛鐘崇墵閹寧銈ｉ崘鈺冨幐闁诲繒鍋犻褔宕濆鍫熺厽闁规崘娉涢弸娑㈡煛瀹€鈧崰鏍蓟閸ヮ剚鏅濋柍褜鍓欓埢宥咁潨閳ь剟寮婚敓鐘插耿闁宠桨鑳舵禒鎼佹⒑閸濆嫭婀扮紒瀣崌閸┾偓妞ゆ帒锕︾粔鐢告煕鐎ｎ亝鍣归柣锝呭槻閻ｆ繈宕熼鍌氬箰濠电偠鎻徊鍧楀箠閹惧瓨鍙忛柛顐犲劜閻撴瑦銇勯弽銊р姇妞ゃ儱鐗撻弻娑㈠Ω閳哄啰鏆Δ鐘靛仦閹瑰洭鐛幒鎴旀斀闁搞儴鍩栭敍鍛磽?/ 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鎯у⒔閹虫捇鈥旈崘顏佸亾閿濆簼绨奸柟鐧哥秮閺岋綁顢橀悙鎼闂侀潧妫欑敮鎺楋綖濠靛鏅查柛娑卞墮椤ユ艾鈹戞幊閸婃鎱ㄩ悜钘夌；闁绘劗鍎ら崑瀣煟濡崵婀介柍褜鍏涚欢姘嚕閹绢喖顫呴柍鈺佸暞閻濇牠姊绘笟鈧埀顒傚仜閼活垱鏅堕弶娆剧唵閻熸瑥瀚粈瀣偓瑙勬礈閸忔﹢銆佸鈧幃鈺冨枈婢跺苯绨ラ梻鍌欐祰椤曆囧礄閻ｅ瞼绀婇柛鈩冪☉绾惧鏌熼幑鎰厫妞ゎ偅娲熼弻宥夊传閸曨偀鍋撻懡銈囦笉闁告挆鈧崑鎾绘偡閺夋妫岄梺鍝ュУ濞叉粓鎳炴潏銊х瘈婵﹩鍓涢悾楣冩⒑缂佹ɑ鐓ラ柛姘儔閸╂盯骞嬮敂钘夆偓鐢告煕閿旇骞栭弽锟犳⒑闂堟稒顥滈柛鐔告尦瀵鏁愭径濠勵唺闂佺粯鍔楅弫鎼佸汲閵堝鈷戦悹鍥ｂ偓铏亶濡炪們鍔岄敃顏堝Υ娴ｈ倽鏃堝川椤撶媭妲规俊鐐€栭崹鍏兼叏閵堝洠鍋撳顑惧仮婵﹥妞介幊锟犲Χ閸涱喚鈧箖姊洪懡銈呮瀭闁稿孩濞婇崺鈧い鎺嶇閸ゎ剟鏌涢幘瀵搞€掗柛鎺撳浮瀹曞ジ濡烽妷褜妲版俊鐐€栧濠氬疾椤愶箑鍌ㄩ梺顒€绉甸埛鎴︽煕閹邦剙绾ч柟顖氱墦閺屾稒绻濋崟顓炵闂佸搫鎳庨悥濂稿箖閻ｅ苯鏋堟俊顖濇〃婢规洟鏌ｉ悢鍝ユ噧閻庢凹鍘炬竟鏇熺節濮橆厾鍘卞┑掳鍊愰崑鎾绘煕閻旈攱鍋ラ柟顕€绠栭幃婊堟寠婢跺矈鏀ㄩ梻浣虹帛閸斿繘寮插鍫稏鐎广儱鎳夐弨浠嬫煟閹邦剙绾фい銉у仱閺屾盯濡歌閺嗩剟鏌ｅ☉鍗炴珝鐎规洖銈告俊鐑芥晜鐟欏嫬顏烘繝鐢靛仩閹活亞绱為埀顒併亜椤愩埄妯€闁诡喗锕㈤弻鍡楊吋閸℃瑥骞愰梻浣告啞娓氭宕板顑炶櫣鈧數纭堕崑鎾舵喆閸曨剙顦╅梺鎼炲妼閻栫厧鐣峰ú顏呮櫢闁绘灏欓ˇ銊╂⒑閸愬弶鎯堥柨鏇樺€栫粋鎺懨洪鍛嫽闂佺鏈悷褏鎷规导瀛樼厱闁规儳顕幊鍛磼椤旇姤顥堥柟顔荤矙瀹曘劍绻濋崒娆戞殫濠电姷鏁搁崑鐐哄垂椤栫偛鍨傛繛宸簼閸嬪倿鏌￠崶銉ョ仾闁绘挸鍟撮弻宥嗘姜閹殿噮妲梺鍝勬閻熴儵鍩為幋锔绘晩闁稿繒鍘ч弸鐘绘⒑閸濆嫭婀伴柣鈺婂灠椤曪綁顢氶埀顒勭嵁濮椻偓瀹曟粍鎷呯憴鍕靛晫闂傚倸鍊风粈渚€骞栭锔藉剹濠㈣泛鏈～鏇㈡煛閸モ晛鏋旀い鈺冨厴閺屻劑寮撮悙娴嬪亾閸濄儳鐭嗛柛顐犲灪閸犳劖绻濇繝鍌滃闁稿﹤鐖奸弻娑樜旈崘銊ュ濠电偞鎸搁…鐑藉蓟閺囥垹閱囨繝闈涚墢椤斿﹪姊烘导娆戠К濞存粠鍓熼崺鈧い鎺嶇贰閸熷繘鏌涢悩宕囧闁哄懓鍩栭幆鏃堝Ω閵壯屽敼闂備線娼х换鎺撴叏椤撶倣锝夊醇閻旂寮垮┑顔筋殔濡绂嶅┑瀣厱闁哄洨鍋涢弳锝嗘叏婵犲懏顏犵紒顔界懇楠炴劖鎯旈姀鈥愁伆闂傚倷鑳剁涵鍫曞疾濞戙垹鐤柡澶嬪灩閺嗭箓鏌熸潏鍓х暠缂佺姴顭烽弻锟犲磼濡搫濮曞銈庡亜缁夌懓顫忓ú顏咁棃婵炴垶姘ㄩ濠冪節濞堝灝鏋涢柣妤佹崌瀵偊宕橀鑲╋紲濠电偞鍨堕懝楣冪嵁瀹ュ鈷掑ù锝堟鐢稒銇勯妸銉﹀殗闁诡啫鍕瘈闁告洦鍓﹂崑銊╂⒑閸濆嫯顫﹂柛搴㈢叀閹繝濮€閵堝棛鍘遍梺瑙勬緲閸氣偓缂併劌顭烽弻锛勨偓锝庡亞濞叉挳鏌″畝瀣？濞寸媴绠撻幃娆擃敆閸屻倖袨闂佽楠搁悘姘熆濡皷鍋撳鐓庡⒋闁糕斂鍎插鍕節鎼淬垹鍏婇梺鍝勵槸閻楀嫰宕濆澶樻晩闊洦姊荤弧鈧梺姹囧灲濞佳勭濠婂嫨浜滈柡鍥ュ妼楠炴鏌熸笟鍨妤犵偞锕㈤、娆撴嚃閳哄﹤鏅ｅ┑锛勫亼閸婃牠宕濊閹虫瑨銇愰幒鎴犲姦濡炪倖甯婇悞锕偹夐崼鈶╁亾鐟欏嫭绀冮柨鏇樺灲閵嗕礁鈻庨幘鍐茬哎婵犮垼顕栭崹鏉棵洪妶鍥╀笉闁革富鍘剧壕鍏笺亜閺冨倸甯堕悽顖樺姂閺屾稓鈧綆鍋呯亸顓㈡煟閿濆洤鍘寸€规洖澧庨幑鍕倻濡崵褰欓梻鍌氬€搁崐鎼佸磹閻戣姤鍊块柨鏇炲€归崕鎴犳喐閻楀牆绗掗柡鍕╁劦閺屾盯寮撮妸銉т哗闂佹悶鍔岄崐鍧楀蓟閻旂厧绠氶柣妤€鐗滃Λ鍕磼閻愵剙鍔ゆい顓犲厴瀵鏁愭径濠冾棟闂佸湱顭堢€涒晠宕曢幘缁樷拺?
     * 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳婀遍埀顒傛嚀鐎氼參宕崇壕瀣ㄤ汗闁圭儤鍨归崐鐐烘偡濠婂啰绠荤€殿喗濞婇弫鍐磼濞戞艾骞堟俊鐐€ら崢浠嬪垂閸偆顩叉繝闈涱儐閻撴洘绻涢崱妤冪缂佺姴顭烽弻鈥崇暆閳ь剟宕伴幘鑸殿潟闁圭儤顨呴～鍛存煟濡櫣锛嶇憸閭﹀灦濮婄粯鎷呴懞銉ｂ偓鍐磼閳ь剚鎷呯悰鈥充壕婵﹩鍋勫畵鍡欌偓娈垮枦椤曆囧煡婢跺á鐔兼煥鐎ｅ灚缍屽┑鐘垫暩閸嬫稑螞濞嗘挸绀夋俊銈呭暟閻瑩鏌涢妷顔煎闁抽攱鍨堕幈銊╂偡閻楀牊鎮欓梺閫炲苯鍘甸柛濠冪箓閻ｉ攱瀵奸弶鎴濆敤濡炪倖鎸鹃崰鎾绘偩閹惰姤鈷掗柛灞剧懆閸忓本銇勯鐐靛ⅱ闁瑰箍鍨介獮鍥级閼愁垍鏇㈡煟鎼搭垳绉甸柛鎾寸〒缁牊绻濋崶銊у幍闁哄鐗撶粻鏍ь瀶椤曗偓閺岋綁骞樼€靛憡鍒涢梺璇″枟椤ㄥ﹪寮幇鏉跨＜婵炴垶鐟цぐ鍥╃磽閸屾瑧鍔嶉柛鏃€鐗曡灋闁告劦鍠栭拑鐔兼煥濞戞ê顏ф繛宀婁邯閺岋綁骞囬棃娑橆潾濡炪倧缂氶崡鎶藉箖濡ゅ啯鍠嗛柛鏇ㄥ墰椤︻參姊洪崨濠庣劶闁搞儜鍛箣闂備胶顢婇幓顏嗙不閹寸姷涓嶆繛鎴炵懀娴滄粓鏌熼崜褜鍔滅紒鎲嬬節閺屾盯濡烽敐鍛ㄩ梺鍦焿濞咃絿妲愰幘璇茬＜婵炲棙鍨垫俊浠嬫煢閸愵喕鎲鹃柡宀€鍠栭、娑橆潩閸楃偐鍙＄紓鍌欐祰妞存悂骞愰幖渚囨晣濠靛倻顭堢粈瀣亜閹捐泛啸鐎规洘鐓￠弻锝嗘償閵忊晛鏅遍梺鍝ュУ閻楃娀鍨鹃敃鍌涘€婚柦妯侯槺閻ｆ椽姊洪棃娑氱疄闁稿﹥鐗犲畷鎴﹀磼閻愯尙顔愰柡澶婄墕婢т粙骞冩總鍛婄厸闁糕剝娲栧畵鍡涙煛瀹€瀣М闁诡喓鍨藉畷顐﹀Ψ瑜忛崢鎰版⒒閸屾瑧顦﹂幖瀛樼矌瀵板﹥绂掔€ｎ偄浠奸梺缁樺灱濡嫮绮婚鈧弻銈夊箒閹烘垵濮庢繛瀵稿Т閵堟悂骞冨Δ鍛濠㈣泛锕ｆ竟鏇㈡⒒娓氣偓閳ь剛鍋涢懟顖涙櫠閺夋鐔嗛悷娆忓缁€鍐磼鏉炴壆鐭欑€规洏鍔嶇换婵嬪礋椤愩垻顓奸梻鍌氬€搁崐椋庢閿熺姴鐭楅煫鍥ㄦ礈娑撳秹鏌熺€涙绠ラ柛銈嗘礋閺岋綁骞囬棃娑橆潾缂備胶濞€缁犳牠寮诲☉銏╂晝闁靛牆鎳忛悘渚€姊哄ú璇插箺閻㈩垽绻濆濠氬灳瀹曞洦娈曢柣搴秵閸撴盯鏁嶉悢鍝ョ閻庣數顭堥鎾斥攽閳ヨ櫕鍠樻鐐茬箻閹晝鎲楁担鍛婅础闁逞屽墾缂嶅棙绂嶅▎鎰彾闁哄洨鍋愰弨浠嬫煟濡偐甯涙繛鎳峰嫪绻嗘い鎰剁悼濞插鈧鍠楁繛濠囧极閸屾稒鍙忛柟閭︿簽閻╁酣姊绘担绛嬫綈婵犮垺蓱閺呰埖鎯旈妸锕€浠奸柡澶婄墐閺呮繄澹曟總鍛婄厓鐟滄粓宕滃☉銏犵劦妞ゆ帒锕︾粔鐢告煕鐎ｂ晝鍔嶇紒鍌氱Т椤劑宕奸悢鍝勫箥闂傚倷绶￠崣蹇曠不閹达箑鍑犳繛鎴欏灪閻撴盯鎮楅敐搴濋偗妞ゅ孩顨婂Λ浣瑰緞鐎ｎ剛鐦堟繝鐢靛Т閸婃悂顢旈埡鍛厱闁哄倽顕ч埀顒佺箞瀵顓奸崶銊ユ瀭闂佸憡娲﹂崑鍡樺瀹€鍕拺閻犲洠鈧櫕鐏€闂佸搫鎳忕换鍫ュ春閳ь剚銇勯幒鍡椾壕濠电姭鍋撻弶鍫涘妽閸欏繘鏌熺紒銏犳殙濠㈣泛艌閺€浠嬫煕椤愮姴鐏柨?闂?NewAPI 闂?aicoming.top
     *
     * 关键坑（已踩）：
     *   - body.prompt 必须顶层（aicoming 强制要求）
     *   - input_reference 支持多张（同名 multipart part）
     *   - duration 是字符串 "4" 不是 int
     *   - aicoming-video-proxy 要求至少一个 multipart file，文生视频也要传占位
     *
     * @param prompt         用户输入提示词
     * @param imageBytes     上游图片字节（文生视频时传 null，内部用占位图）
     * @param imageFilename  文件名（aicoming 用来识别格式）
     * @param imageMime      MIME 类型，如 image/png
     * @param duration       视频时长（秒）
     * @param resolution     分辨率，如 480P
     * @return               NewAPI 返回的 task_id
     */
    public String submitVideo(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution) {
        return submitVideo(prompt, imageBytes, imageFilename, imageMime, duration, resolution, null);
    }

    /**
     * 图生视频：调 NewAPI /v1/videos（走 aicoming-video-proxy）
     *
     * 必填字段（从 jurong-api-nodes/api_client.py 确认，跟能跑通的 Python 版本一致）：
     *   - model = "doubao-seedance-2.0"（唯一实测能处理 image ref 的模型）
     *   - prompt （顶层）
     *   - duration 字符串（如 "4"）
     *   - resolution "480P" / "720p" / "1080p" / "4k"
     *   - input_reference 第一帧图（multipart file）
     *
     * 注意：**不传 ratio 和 watermark**。Python 参考版本没这俩字段。
     * 我们之前多塞了可能让 aicoming 误判（已改回去）。
     */
    public String submitVideo(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution, String ratio) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model", "doubao-seedance-2.0");
            builder.part("prompt", prompt);
            builder.part("duration", String.valueOf(duration));
            builder.part("resolution", resolution);
            // ratio / watermark 不传，参考能跑通的 Python api_client.py

            if (imageBytes != null && imageBytes.length > 0) {
                // 图生视频：传上游图片
                // 与 Python api_client.py 对齐：同时发 3 个字段名，兼容 doubao-seedance / aicoming-proxy
                final String fname = imageFilename != null ? imageFilename : "canvas_input.png";
                final String mime = imageMime != null ? imageMime : "image/png";
                ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                    @Override
                    public String getFilename() { return fname; }
                };
                MediaType mediaType = MediaType.parseMediaType(mime);
                builder.part("image", imageResource, mediaType);
                builder.part("input_reference", imageResource, mediaType);
                builder.part("image_url", imageResource, mediaType);
            } else {
                // 文生视频：aicoming 也要求一个 file 字段，传 16x16 透明 PNG 占位
                final String placeholderName = "_placeholder.png";
                ByteArrayResource placeholderResource = new ByteArrayResource(DUMMY_PNG_BYTES) {
                    @Override
                    public String getFilename() { return placeholderName; }
                };
                builder.part("image", placeholderResource, MediaType.IMAGE_PNG);
                builder.part("input_reference", placeholderResource, MediaType.IMAGE_PNG);
                builder.part("image_url", placeholderResource, MediaType.IMAGE_PNG);
            }

            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/videos")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(600))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("NewAPI /v1/videos failed: {} body={}", e.getStatusCode(), body);
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        parseErrorMessage(body, e.getStatusCode().value()));
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 视频提交返回空");
            }
            String taskId = response.path("id").asText(response.path("task_id").asText(""));
            if (taskId.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 响应里没找到 task_id: " + response);
            }
            log.info("NewAPI video task submitted: {} (image={}, size={}B, duration={}s, resolution={})",
                taskId,
                imageBytes != null ? imageFilename : "placeholder",
                imageBytes != null ? imageBytes.length : 0,
                duration, resolution);
            return taskId;
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI submitVideo failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    /**
     * 通过 asset_url 引用素材提交视频生成任务（严格按 Assets-API 参考手册 §5）。
     *
     * <p>与 {@link #submitVideo(String, byte[], String, String, int, String)} 的区别：
     * <ul>
     *   <li>后者：multipart 直传图片字节到 /v1/videos（一段式）</li>
     *   <li>本方法：图片先上传到 proxy 8080 /v1/assets 拿 asset_url，再以 JSON body 引用（两段式）</li>
     * </ul>
     *
     * <p>请求体（手册 §5 端到端流程，请求体字段名 image_urls 与 asset_url 二选一，已确认用 image_urls）：
     * <pre>{@code
     * {
     *   "model": "doubao-seedance-2.0",
     *   "prompt": "用户提示词",
     *   "image_urls": ["asset://aic_xxx"],
     *   "duration": "4",        // 字符串，不是 int（api_client.py 已踩坑）
     *   "resolution": "480P"
     * }
     * }</pre>
     *
     * <p>走 NewAPI 3000（不是 proxy 8080）。Header: Authorization: Bearer ${newapi.token}。
     *
     * @param prompt     用户提示词（原样传，不做 enhance）
     * @param assetUrl   形如 asset://aic_xxx（必须 status=active 之后才能引用）
     * @param model      模型名，默认 doubao-seedance-2.0
     * @param duration   时长（秒）
     * @param resolution 分辨率，如 480P / 720P
     * @return NewAPI 返回的 task_id（id 或 task_id 字段）
     */
    public String submitVideoWithAsset(String prompt, String assetUrl,
                                       String model, int duration, String resolution) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        if (assetUrl == null || assetUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "assetUrl 不能为空");
        }
        final String useModel = (model != null && !model.isBlank()) ? model : "doubao-seedance-2.0";
        // aicoming 只接受小写 resolution（480p/720p/1080p/4k），大写会报 invalid_resolution
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution.toLowerCase() : "480p";

        // 请求体：image_urls 数组引用 asset_url（手册 §5）
        Map<String, Object> body = new HashMap<>();
        body.put("model", useModel);
        body.put("prompt", prompt);
        body.put("image_urls", List.of(assetUrl));
        body.put("duration", String.valueOf(duration));   // 字符串，不是 int
        body.put("resolution", useResolution);

        // 关键日志：打印完整请求体（不含 token），方便排查 image_urls vs asset_url 字段名问题
        log.info("[VIDEO-SUBMIT] → POST {}/v1/videos (JSON): model={}, promptLen={}, image_urls=[{}], "
                + "duration={}, resolution={}",
            baseUrl, useModel, prompt.length(), assetUrl, duration, useResolution);
        log.info("[VIDEO-SUBMIT] → 完整请求体: {}", body);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/videos")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(600))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String respBody = e.getResponseBodyAsString();
                    log.error("[VIDEO-SUBMIT] ← HTTP {}: body={}", e.getStatusCode(), respBody);
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        parseErrorMessage(respBody, e.getStatusCode().value()));
                })
                .block();

            // 关键日志：打印 NewAPI 完整响应，能看到 aicoming 实际接受/拒绝的字段
            log.info("[VIDEO-SUBMIT] ← 响应: {}", truncateForLog(
                response == null ? "null" : response.toString(), 2000));

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 视频提交（asset 模式）返回空");
            }
            String taskId = response.path("id").asText(response.path("task_id").asText(""));
            if (taskId.isEmpty()) {
                log.error("[VIDEO-SUBMIT] ← 响应中没找到 task_id: {}", response);
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 响应里没找到 task_id: " + response);
            }
            log.info("[VIDEO-SUBMIT] ← 提交成功: taskId={}, assetUrl={}, duration={}s, resolution={}",
                taskId, assetUrl, duration, useResolution);
            return taskId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[VIDEO-SUBMIT] ← 异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    // 16x16 透明 PNG 占位图（文生视频时 aicoming-video-proxy 强制要求至少一个 file 字段）
    // 用 ImageIO 动态生成，避免手敲 hex / 拷 Python 语法错误
    private static final byte[] DUMMY_PNG_BYTES = createPlaceholderPng();

    private static byte[] createPlaceholderPng() {
        try {
            // BufferedImage 初始为全透明(0,0,0,0)，Aicoming 不会在意图片内容
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();
            log.info("Generated placeholder PNG: {} bytes", bytes.length);
            return bytes;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to generate placeholder PNG bytes", e);
        }
    }

    /**
     * 从 NewAPI 响应中提取视频下载 URL
     * 兼容多种返回格式
     */
    public String extractVideoUrl(JsonNode pollResult) {
        if (pollResult == null) return null;

        // 形态 1：metadata.url
        JsonNode metadata = pollResult.get("metadata");
        if (metadata != null && metadata.isObject()) {
            JsonNode url = metadata.get("url");
            if (url != null && url.isTextual()) {
                return url.asText();
            }
        }

        // 形态 2：result.metadata.url
        JsonNode result = pollResult.get("result");
        if (result != null && result.isObject()) {
            JsonNode innerMeta = result.get("metadata");
            if (innerMeta != null && innerMeta.isObject()) {
                JsonNode url = innerMeta.get("url");
                if (url != null && url.isTextual()) {
                    return url.asText();
                }
            }
        }

        // 形态 3：直接在 result 里
        if (result != null) {
            JsonNode url = result.get("url");
            if (url != null && url.isTextual()) {
                return url.asText();
            }
        }

        // 形态 4：顶层 url
        JsonNode topUrl = pollResult.get("url");
        if (topUrl != null && topUrl.isTextual()) {
            return topUrl.asText();
        }

        log.warn("Could not extract video URL from NewAPI response: {}", pollResult);
        return null;
    }

    /**
     * 快速健康检查 —— 检测 NewAPI 服务是否可用
     * <p>
     * GET /v1/models，超时 5 秒。
     * 用于在正式调用前快速判断 NewAPI 是否可达。
     *
     * @return true 表示服务可用
     */
    public boolean checkHealth() {
        try {
            webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/v1/models")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .block();
            return true;
        } catch (Exception e) {
            log.warn("NewAPI health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 调用 NewAPI 图片生成接口（gpt-image-2-2k）
     * <p>
     * POST /v1/images/generations
     * 超时：5 分钟（300 秒）。
     * 降级：调用前先检查 NewAPI 健康状态，不可达时直接抛异常。
     *
     * @param prompt  图片生成提示词
     * @param size    图片尺寸，默认 1024x1024
     * @param quality 图片质量，默认 standard
     * @param style   图片风格，默认 vivid
     * @return 生成的图片 URL
     */
    public String generateImage(String prompt, String size, String quality, String style) {
        // 快速健康检查，5 秒超时，避免 NewAPI 不可达时长时间挂起
        if (!checkHealth()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 服务不可用，请稍后再试");
        }

        // 构建请求体
        // gpt-image-2-2k 模型使用 b64_json 格式返回图片数据
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-image-2-2k");
        body.put("prompt", prompt);
        body.put("size", size != null ? size : "1024x1024");
        body.put("quality", quality != null ? quality : "standard");
        body.put("style", style != null ? style : "vivid");
        body.put("response_format", "b64_json");

        try {
            // 调用 NewAPI 图片生成接口，超时 5 分钟
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/images/generations")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(300))  // 5 分钟超时
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/images/generations failed: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "图片生成失败: " + e.getStatusCode() + " " + e.getStatusText());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI generateImage error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成返回为空（响应为 null）");
            }

            // 打印响应结构用于调试
            java.util.List<String> topFields = new java.util.ArrayList<>();
            response.fieldNames().forEachRemaining(topFields::add);
            log.info("NewAPI 响应顶层字段: {}", topFields);

            // 检查 data 数组
            if (!response.has("data")) {
                // 有些 NewAPI 可能直接返回 URL 在顶层
                if (response.has("url") && response.get("url").isTextual()) {
                    String imageUrl = response.get("url").asText();
                    log.info("NewAPI image generated OK (top-level url): {}", imageUrl);
                    return imageUrl;
                }
                // 检查顶层 b64_json
                if (response.has("b64_json") && response.get("b64_json").isTextual()) {
                    String b64Data = response.get("b64_json").asText();
                    log.info("NewAPI image generated OK (top-level b64_json): b64Len={}", b64Data.length());
                    return "data:image/png;base64," + b64Data;
                }
                // 返回错误信息
                String errMsg = response.has("error") ? response.get("error").toString() : "未知错误";
                log.error("NewAPI generateImage 返回错误: {}, 完整响应: {}", errMsg, response.toString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 返回错误: " + errMsg);
            }

            JsonNode dataArray = response.get("data");
            if (!dataArray.isArray() || dataArray.size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成 data 数组为空");
            }

            JsonNode first = dataArray.get(0);
            // 打印 data[0] 字段名用于调试
            java.util.List<String> firstFieldNames = new java.util.ArrayList<>();
            first.fieldNames().forEachRemaining(firstFieldNames::add);
            log.info("NewAPI data[0] 字段: {}", firstFieldNames);

            // 尝试多种可能的 URL 字段名
            String[] urlFields = {"url", "image_url", "imageUrl", "img_url", "imgUrl"};
            for (String field : urlFields) {
                if (first.has(field) && first.get(field).isTextual()) {
                    String imageUrl = first.get(field).asText();
                    log.info("NewAPI image generated OK ({}): promptLen={}", field, prompt.length());
                    return imageUrl;
                }
            }

            // 检查 b64_json 字段 — 返回 data URI 前缀的 base64 字符串
            if (first.has("b64_json") && first.get("b64_json").isTextual()) {
                String b64Data = first.get("b64_json").asText();
                log.info("NewAPI image generated OK (b64_json): promptLen={}, b64Len={}", prompt.length(), b64Data.length());
                return "data:image/png;base64," + b64Data;
            }

            // data[0] 中未找到图片数据字段，将实际字段名包含在错误信息中
            String dataContent = first.toString().length() > 500 ? first.toString().substring(0, 500) : first.toString();
            log.error("NewAPI data[0] 中未找到图片数据字段。data[0] 字段: {}, 内容: {}", firstFieldNames, dataContent);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "图片生成响应中未找到图片数据字段，data[0] 包含字段: " + firstFieldNames);
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI generateImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成失败: " + e.getMessage());
        }
    }

    /**
     * 简化版图片生成：使用默认参数
     *
     * @param prompt 图片生成提示词
     * @return 生成的图片 URL
     */
    public String generateImage(String prompt) {
        return generateImage(prompt, null, null, null);
    }

    /**
     * 调用 NewAPI 图片编辑接口（/v1/images/edits）
     * 将用户引用的图片作为素材，结合提示词生成新图片。
     * 使用 multipart/form-data 格式上传引用图片。
     *
     * @param prompt          生成提示词
     * @param referenceImages 引用图片列表（base64 data URI 格式，如 data:image/png;base64,...）
     * @param size            图片尺寸
     * @param quality         图片质量
     * @param style           图片风格
     * @return 生成的图片（base64 data URI 格式或 URL）
     */
    public String editImage(String prompt, List<String> referenceImages,
                            String size, String quality, String style) {
        if (!checkHealth()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 服务不可用，请稍后再试");
        }

        // 构建 multipart 请求体
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();

        // 添加文本参数
        bodyBuilder.part("model", "gpt-image-2-2k");
        bodyBuilder.part("prompt", prompt);
        bodyBuilder.part("size", size != null ? size : "1024x1024");
        if (quality != null) bodyBuilder.part("quality", quality);
        if (style != null) bodyBuilder.part("style", style);
        bodyBuilder.part("response_format", "b64_json");

        // 解码 base64 引用图片并添加为 multipart 文件
        int imgIndex = 0;
        for (String dataUri : referenceImages) {
            try {
                byte[] imageBytes = decodeDataUri(dataUri);
                String mimeType = getMimeTypeFromDataUri(dataUri);
                String ext = mimeType.equals("image/jpeg") ? ".jpg" : ".png";
                final int currentIdx = imgIndex;

                // gpt-image 模型支持多张参考图，使用 image 字段
                bodyBuilder.part("image", new ByteArrayResource(imageBytes) {
                    @Override
                    public String getFilename() {
                        return "reference_" + currentIdx + ext;
                    }
                }).contentType(MediaType.parseMediaType(mimeType));
                imgIndex++;
            } catch (Exception e) {
                log.warn("解码引用图片 {} 失败: {}", imgIndex, e.getMessage());
                imgIndex++;
            }
        }

        if (imgIndex == 0) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "引用图片解码失败，无法进行图片编辑");
        }

        log.info("调用 NewAPI /v1/images/edits: promptLen={}, refImageCount={}", prompt.length(), imgIndex);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/images/edits")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(bodyBuilder.build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(300))  // 5 分钟超时
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/images/edits failed: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "图片编辑失败: " + e.getStatusCode() + " " + e.getStatusText());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI editImage error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑返回为空（响应为 null）");
            }

            // 打印响应结构用于调试
            java.util.List<String> topFields = new java.util.ArrayList<>();
            response.fieldNames().forEachRemaining(topFields::add);
            log.info("NewAPI editImage 响应顶层字段: {}", topFields);

            // 解析响应（与 generateImage 相同的逻辑）
            if (!response.has("data")) {
                if (response.has("url") && response.get("url").isTextual()) {
                    return response.get("url").asText();
                }
                if (response.has("b64_json") && response.get("b64_json").isTextual()) {
                    return "data:image/png;base64," + response.get("b64_json").asText();
                }
                String errMsg = response.has("error") ? response.get("error").toString() : "未知错误";
                log.error("NewAPI editImage 返回错误: {}, 完整响应: {}", errMsg, response.toString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 返回错误: " + errMsg);
            }

            JsonNode dataArray = response.get("data");
            if (!dataArray.isArray() || dataArray.size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑 data 数组为空");
            }

            JsonNode first = dataArray.get(0);
            java.util.List<String> firstFieldNames = new java.util.ArrayList<>();
            first.fieldNames().forEachRemaining(firstFieldNames::add);
            log.info("NewAPI editImage data[0] 字段: {}", firstFieldNames);

            // 检查 b64_json
            if (first.has("b64_json") && first.get("b64_json").isTextual()) {
                String b64Data = first.get("b64_json").asText();
                log.info("NewAPI editImage OK (b64_json): b64Len={}", b64Data.length());
                return "data:image/png;base64," + b64Data;
            }

            // 检查 URL 字段
            String[] urlFields = {"url", "image_url", "imageUrl"};
            for (String field : urlFields) {
                if (first.has(field) && first.get(field).isTextual()) {
                    String imageUrl = first.get(field).asText();
                    log.info("NewAPI editImage OK ({}): {}", field, imageUrl);
                    return imageUrl;
                }
            }

            String dataContent = first.toString().length() > 500 ? first.toString().substring(0, 500) : first.toString();
            log.error("NewAPI editImage data[0] 中未找到图片数据字段。字段: {}, 内容: {}", firstFieldNames, dataContent);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "图片编辑响应中未找到图片数据字段，data[0] 包含字段: " + firstFieldNames);
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI editImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑失败: " + e.getMessage());
        }
    }

    /**
     * 从 base64 data URI 中解码图片字节
     * 支持格式：data:image/png;base64,xxxx 或 data:image/jpeg;base64,xxxx
     */
    private byte[] decodeDataUri(String dataUri) {
        String base64Data;
        if (dataUri.startsWith("data:")) {
            // data:image/png;base64,xxxx
            int commaIdx = dataUri.indexOf(",");
            if (commaIdx == -1) {
                throw new IllegalArgumentException("无效的 data URI 格式");
            }
            base64Data = dataUri.substring(commaIdx + 1);
        } else {
            base64Data = dataUri;
        }
        return Base64.getDecoder().decode(base64Data);
    }

    /**
     * 从 data URI 中提取 MIME 类型
     */
    private String getMimeTypeFromDataUri(String dataUri) {
        if (dataUri.startsWith("data:image/jpeg")) return "image/jpeg";
        if (dataUri.startsWith("data:image/jpg")) return "image/jpeg";
        if (dataUri.startsWith("data:image/webp")) return "image/webp";
        return "image/png"; // 默认 png
    }

    /**
     * 从 NewAPI 错误响应中提取用户可读的错误信息。
     * NewAPI 的错误体有多层嵌套 JSON，比如：
     * <pre>
     * {"code":"fail_to_fetch_task","message":"{\"error\":{\"message\":\"insufficient balance\",\"type\":\"insufficient_quota\"}}"}
     * </pre>
     */
    private String parseErrorMessage(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.isBlank()) {
            return "视频任务提交失败: HTTP " + statusCode;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // 尝试解析嵌套的 message 字段
            String rawMessage = root.path("message").asText(null);
            if (rawMessage != null && !rawMessage.isBlank()) {
                try {
                    JsonNode inner = objectMapper.readTree(rawMessage);
                    JsonNode errorNode = inner.path("error");
                    String type = errorNode.path("type").asText(null);
                    String detail = errorNode.path("message").asText(null);
                    if (type != null) {
                        String chineseMsg = translateErrorType(type);
                        return chineseMsg + (detail != null ? "（" + detail + "）" : "");
                    }
                    if (detail != null) {
                        return "视频任务提交失败: " + detail;
                    }
                } catch (Exception ignored) {
                    // message 不是 JSON，直接用
                }
                return "视频任务提交失败: " + rawMessage;
            }
            // 尝试顶层 error 字段
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String msg = errorNode.path("message").asText(null);
                if (msg != null) return translateErrorType(errorNode.path("type").asText("")) + "（" + msg + "）";
            }
        } catch (Exception ignored) {}
        return "视频任务提交失败: HTTP " + statusCode;
    }

    /** 将 NewAPI 错误类型映射为用户可读的中文提示 */
    private String translateErrorType(String type) {
        if (type == null) return "视频任务提交失败";
        return switch (type) {
            case "insufficient_quota" -> "账户余额不足，无法提交视频任务";
            case "insufficient_balance" -> "账户余额不足，请充值后重试";
            case "invalid_resolution" -> "不支持的分辨率参数";
            case "invalid_param" -> "请求参数有误";
            case "rate_limit_exceeded" -> "请求过于频繁，请稍后重试";
            case "content_policy_violation" -> "内容不符合安全策略，请修改提示词";
            default -> "视频任务提交失败（" + type + "）";
        };
    }

    /**
     * 音频转文字（ASR），调 NewAPI /v1/audio/transcriptions。
     *
     * @param audioBytes 音频二进制（wav/mp3）
     * @param mimeType   MIME 类型，如 audio/wav
     * @return 识别结果列表，每段含 start (秒), end (秒), text (文本)
     */
    public List<Map<String, Object>> audioTranscribe(byte[] audioBytes, String mimeType) {
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("[ASR] audioBytes 为空，返回空列表");
            return List.of();
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", "whisper-1");
        final String filename = "audio." + (mimeType != null && mimeType.contains("wav") ? "wav" : "mp3");
        builder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() { return filename; }
        }, MediaType.parseMediaType(mimeType != null ? mimeType : "audio/wav"));

        try {
            JsonNode resp = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(120))
                .block();

            // 响应的 segments 字段是数组 [{start, end, text}, ...]
            JsonNode segments = resp != null ? resp.path("segments") : null;
            if (segments == null || !segments.isArray() || segments.size() == 0) {
                log.warn("[ASR] segments 为空，resp={}", resp);
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode seg : segments) {
                Map<String, Object> m = new HashMap<>();
                m.put("start", seg.path("start").asDouble(0.0));
                m.put("end", seg.path("end").asDouble(0.0));
                m.put("text", seg.path("text").asText(""));
                result.add(m);
            }
            log.info("[ASR] 识别完成: {} segments, {} bytes", result.size(), audioBytes.length);
            return result;
        } catch (Exception e) {
            log.error("[ASR] 失败: {}", e.getMessage());
            throw e instanceof BusinessException ? (BusinessException) e
                : new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "ASR 失败: " + e.getMessage());
        }
    }

    /**
     * 批量视觉 caption：一次传多张图片 URL 给 VL 模型，返回每张图的描述。
     *
     * @param imageUrls 图片公网 URL 列表（1-3 张）
     * @param prompt    给 VL 模型的指令
     * @return 每张图的 caption，顺序与 imageUrls 一致
     */
    public List<Map<String, String>> visionCaptionBatch(List<String> imageUrls, String prompt) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        // 构建多图 content 数组：[{type:"text", text:prompt}, {type:"image_url", image_url:{url:url1}}, ...]
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);
        for (String url : imageUrls) {
            Map<String, Object> imgPart = new HashMap<>();
            imgPart.put("type", "image_url");
            imgPart.put("image_url", Map.of("url", url));
            content.add(imgPart);
        }

        Map<String, Object> userMsg = Map.of("role", "user", "content", content);
        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("messages", List.of(userMsg));
        body.put("max_tokens", 2048);

        try {
            JsonNode resp = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(180))
                .block();

            if (resp == null || !resp.has("choices") || resp.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 模型返回为空");
            }
            String raw = resp.get("choices").get(0).path("message").path("content").asText("");
            if (raw.isBlank()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 模型返回空 content");
            }
            // 尝试解析为 JSON 数组 [{camera, action}, ...]
            String jsonStr = raw.trim();
            // 去掉可能的 markdown 代码块包裹
            if (jsonStr.startsWith("```")) {
                int end = jsonStr.indexOf("\n");
                jsonStr = jsonStr.substring(end + 1);
                if (jsonStr.endsWith("```")) {
                    jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
                }
            }
            List<Map<String, String>> result = objectMapper.readValue(jsonStr,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
            log.info("[VL-BATCH] {} images → {} captions, model={}", imageUrls.size(), result.size(), visionModel);
            return result;
        } catch (Exception e) {
            log.error("[VL-BATCH] 失败: model={}, err={}", visionModel, e.getMessage());
            throw e instanceof BusinessException ? (BusinessException) e
                : new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 批量 caption 失败: " + e.getMessage());
        }
    }

    /** 把超长 JSON 字符串截断到指定长度，方便日志查看 */
    private String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated, totalLen=" + s.length() + ")";
    }

    /**
     * 下载图片 URL 并转为 data URI（data:image/xxx;base64,...）
     * 用于 NewAPI 中转服务器无法访问公网 URL 时，直接内嵌 base64。
     */
    private String downloadAsDataUri(String url) {
        org.springframework.http.ResponseEntity<byte[]> entity = WebClient.builder()
            .defaultHeader("User-Agent", "JurongAI/1.0")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
            .build()
            .get().uri(url).retrieve()
            .toEntity(byte[].class)
            .timeout(Duration.ofSeconds(30))
            .block();
        if (entity == null || entity.getBody() == null || entity.getBody().length == 0) {
            throw new RuntimeException("downloaded empty body: " + url);
        }
        MediaType ct = entity.getHeaders().getContentType();
        String mimeType = ct != null ? ct.toString() : "image/png";
        String b64 = Base64.getEncoder().encodeToString(entity.getBody());
        return "data:" + mimeType + ";base64," + b64;
    }
}
