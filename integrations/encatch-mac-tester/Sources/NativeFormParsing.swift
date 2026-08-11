import Encatch

/// A blocked form, queued for the tester to open from the Interceptor sidebar destination.
struct BlockedFormItem: Identifiable {
    let formId: String
    let title: String
    let questionnaireFields: JSONValue?
    var id: String { formId }
}

/// A single question extracted from `ShowFormResponse.questionnaireFields`. The real schema nests
/// questions under sections in a tree this tester doesn't have a typed model for — so this walks
/// the whole tree and treats any object carrying both a recognizable `type` and `id` key as a
/// question, which is robust to the real `{questions: {id: Question}, sections: [...]}` shape
/// without needing to hard-code it. Ported verbatim from `encatch-ios-tester`'s `NativeForm.swift`.
struct NativeFormQuestion: Identifiable {
    let id: String
    let type: String
    let title: String
}

func parseQuestionnaireFields(_ questionnaireFields: JSONValue?) -> [NativeFormQuestion] {
    var results: [NativeFormQuestion] = []
    func stringField(_ object: [String: JSONValue], _ key: String) -> String? {
        if case .string(let s)? = object[key] { return s }
        return nil
    }
    func walk(_ element: JSONValue) {
        switch element {
        case .object(let object):
            let type = stringField(object, "type") ?? stringField(object, "questionType")
            let id = stringField(object, "id") ?? stringField(object, "questionId")
            if let type, let id {
                let title = stringField(object, "title") ?? stringField(object, "label") ?? stringField(object, "question") ?? id
                results.append(NativeFormQuestion(id: id, type: type, title: title))
            }
            object.values.forEach(walk)
        case .array(let array):
            array.forEach(walk)
        default:
            break
        }
    }
    if let questionnaireFields { walk(questionnaireFields) }
    return results
}

/// Answerable question types this demo form knows how to draw; everything else falls back to a
/// plain text field. `welcome`/`thank_you` are deliberately excluded — those are display-only
/// markers, not answerable questions.
let renderableNativeFormTypes: Set<String> = ["rating", "short_answer", "long_text"]
