import SwiftUI
import UIKit

struct MarkdownContent: View {
    let markdown: String
    var inverted = false

    private var blocks: [MarkdownBlock] {
        MarkdownParser.parse(markdown)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case let .heading(level, text):
                    Text(markdownAttributed(text))
                        .font(headingFont(level))
                        .fontWeight(.semibold)
                        .foregroundStyle(primaryColor)
                        .textSelection(.enabled)

                case let .paragraph(text):
                    Text(markdownAttributed(text))
                        .font(.body)
                        .foregroundStyle(primaryColor)
                        .textSelection(.enabled)

                case let .bullet(text):
                    HStack(alignment: .top, spacing: 8) {
                        Text("•")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(primaryColor)
                        Text(markdownAttributed(text))
                            .font(.body)
                            .foregroundStyle(primaryColor)
                            .textSelection(.enabled)
                    }

                case let .ordered(index, text):
                    HStack(alignment: .top, spacing: 8) {
                        Text("\(index).")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(primaryColor)
                        Text(markdownAttributed(text))
                            .font(.body)
                            .foregroundStyle(primaryColor)
                            .textSelection(.enabled)
                    }

                case let .quote(text):
                    Text(markdownAttributed(text))
                        .font(.callout)
                        .foregroundStyle(secondaryColor)
                        .textSelection(.enabled)
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(quoteBackground, in: RoundedRectangle(cornerRadius: 8))
                        .overlay(alignment: .leading) {
                            Rectangle()
                                .fill(inverted ? Color.white.opacity(0.65) : Color.indigo.opacity(0.5))
                                .frame(width: 3)
                        }

                case let .code(language, code):
                    MarkdownCodeBlock(language: language, code: code, inverted: inverted)

                case let .table(table):
                    MarkdownTableView(table: table, inverted: inverted)

                case .divider:
                    Rectangle()
                        .fill(inverted ? Color.white.opacity(0.35) : Color(.separator).opacity(0.45))
                        .frame(height: 1)
                        .padding(.vertical, 2)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var primaryColor: Color {
        inverted ? .white : .primary
    }

    private var secondaryColor: Color {
        inverted ? .white.opacity(0.82) : .secondary
    }

    private var quoteBackground: Color {
        inverted ? .white.opacity(0.11) : Color.indigo.opacity(0.08)
    }

    private func headingFont(_ level: Int) -> Font {
        switch level {
        case 1:
            return .title3
        case 2:
            return .headline
        default:
            return .subheadline
        }
    }

    private func markdownAttributed(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .inlineOnlyPreservingWhitespace
        )
        var attributed = (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
        if inverted {
            attributed.foregroundColor = .white
        }
        return attributed
    }
}

private struct MarkdownTableView: View {
    let table: MarkdownTable
    let inverted: Bool

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(Array(normalizedHeader.enumerated()), id: \.offset) { _, cell in
                        tableCell(cell, isHeader: true)
                    }
                }

                ForEach(Array(normalizedRows.enumerated()), id: \.offset) { rowIndex, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            tableCell(cell, isHeader: false)
                        }
                    }
                    .background(rowIndex.isMultiple(of: 2) ? rowBackground : Color.clear)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(borderColor, lineWidth: 1)
            )
        }
    }

    private func tableCell(_ text: String, isHeader: Bool) -> some View {
        Text(markdownAttributed(text))
            .font(isHeader ? .caption.weight(.semibold) : .caption)
            .foregroundStyle(primaryColor)
            .textSelection(.enabled)
            .lineLimit(nil)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(minWidth: 96, maxWidth: 220, alignment: .leading)
            .background(isHeader ? headerBackground : Color.clear)
            .overlay(alignment: .trailing) {
                Rectangle()
                    .fill(borderColor)
                    .frame(width: 1)
            }
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(borderColor)
                    .frame(height: 1)
            }
    }

    private var normalizedHeader: [String] {
        normalized(table.header)
    }

    private var normalizedRows: [[String]] {
        table.rows.map(normalized)
    }

    private func normalized(_ cells: [String]) -> [String] {
        let width = max(table.header.count, table.rows.map(\.count).max() ?? 0)
        if cells.count >= width { return Array(cells.prefix(width)) }
        return cells + Array(repeating: "", count: width - cells.count)
    }

    private var primaryColor: Color {
        inverted ? .white : .primary
    }

    private var headerBackground: Color {
        inverted ? Color.white.opacity(0.16) : Color(.tertiarySystemGroupedBackground)
    }

    private var rowBackground: Color {
        inverted ? Color.white.opacity(0.06) : Color(.secondarySystemGroupedBackground).opacity(0.55)
    }

    private var borderColor: Color {
        inverted ? Color.white.opacity(0.18) : Color(.separator).opacity(0.35)
    }

    private func markdownAttributed(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .inlineOnlyPreservingWhitespace
        )
        var attributed = (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
        if inverted {
            attributed.foregroundColor = .white
        }
        return attributed
    }
}

private struct MarkdownCodeBlock: View {
    let language: String?
    let code: String
    let inverted: Bool

    @State private var copied = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(languageLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(headerTextColor)
                Spacer()
                Button {
                    UIPasteboard.general.string = code
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        copied = false
                    }
                } label: {
                    Label(copied ? "已复制" : "复制", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .labelStyle(.titleAndIcon)
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(headerTextColor)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(headerBackground)

            ScrollView(.horizontal, showsIndicators: true) {
                Text(code)
                    .font(.system(.callout, design: .monospaced))
                    .foregroundStyle(codeTextColor)
                    .textSelection(.enabled)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(codeBackground)
        }
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .overlay(
            RoundedRectangle(cornerRadius: 9)
                .stroke(borderColor, lineWidth: 1)
        )
    }

    private var languageLabel: String {
        let value = language?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value?.isEmpty == false ? value!.uppercased() : "CODE"
    }

    private var headerBackground: Color {
        inverted ? Color.white.opacity(0.16) : Color(.tertiarySystemGroupedBackground)
    }

    private var codeBackground: Color {
        inverted ? Color.black.opacity(0.28) : Color(.systemBackground)
    }

    private var headerTextColor: Color {
        inverted ? .white.opacity(0.86) : .secondary
    }

    private var codeTextColor: Color {
        inverted ? .white : .primary
    }

    private var borderColor: Color {
        inverted ? Color.white.opacity(0.2) : Color(.separator).opacity(0.35)
    }
}

private enum MarkdownBlock {
    case heading(level: Int, text: String)
    case paragraph(String)
    case bullet(String)
    case ordered(index: Int, text: String)
    case quote(String)
    case code(language: String?, code: String)
    case table(MarkdownTable)
    case divider
}

private struct MarkdownTable {
    let header: [String]
    let rows: [[String]]
}

private enum MarkdownParser {
    static func parse(_ markdown: String) -> [MarkdownBlock] {
        let lines = markdown.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var blocks: [MarkdownBlock] = []
        var paragraph: [String] = []
        var codeLines: [String] = []
        var codeLanguage: String?
        var insideCode = false

        func flushParagraph() {
            let text = paragraph.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                blocks.append(.paragraph(text))
            }
            paragraph.removeAll()
        }

        var index = 0
        while index < lines.count {
            let line = lines[index]
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            if trimmed.hasPrefix("```") {
                if insideCode {
                    blocks.append(.code(language: codeLanguage, code: codeLines.joined(separator: "\n")))
                    codeLines.removeAll()
                    codeLanguage = nil
                    insideCode = false
                } else {
                    flushParagraph()
                    codeLanguage = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
                    insideCode = true
                }
                index += 1
                continue
            }

            if insideCode {
                codeLines.append(line)
                index += 1
                continue
            }

            if trimmed.isEmpty {
                flushParagraph()
                index += 1
                continue
            }

            if trimmed == "---" || trimmed == "***" {
                flushParagraph()
                blocks.append(.divider)
                index += 1
                continue
            }

            if let table = table(from: lines, startingAt: index) {
                flushParagraph()
                blocks.append(.table(table.value))
                index = table.nextIndex
                continue
            }

            if let heading = heading(from: trimmed) {
                flushParagraph()
                blocks.append(heading)
                index += 1
                continue
            }

            if let bullet = bullet(from: trimmed) {
                flushParagraph()
                blocks.append(.bullet(bullet))
                index += 1
                continue
            }

            if let ordered = ordered(from: trimmed) {
                flushParagraph()
                blocks.append(ordered)
                index += 1
                continue
            }

            if trimmed.hasPrefix(">") {
                flushParagraph()
                let quote = String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces)
                blocks.append(.quote(quote))
                index += 1
                continue
            }

            paragraph.append(line)
            index += 1
        }

        if insideCode {
            blocks.append(.code(language: codeLanguage, code: codeLines.joined(separator: "\n")))
        }
        flushParagraph()

        return blocks.isEmpty ? [.paragraph(markdown)] : blocks
    }

    private static func heading(from line: String) -> MarkdownBlock? {
        var level = 0
        for character in line {
            if character == "#" {
                level += 1
            } else {
                break
            }
        }
        guard (1...6).contains(level),
              line.dropFirst(level).first == " " else {
            return nil
        }
        let text = String(line.dropFirst(level + 1))
        return .heading(level: level, text: text)
    }

    private static func bullet(from line: String) -> String? {
        guard line.hasPrefix("- ") || line.hasPrefix("* ") else { return nil }
        return String(line.dropFirst(2))
    }

    private static func ordered(from line: String) -> MarkdownBlock? {
        guard let dotIndex = line.firstIndex(of: ".") else { return nil }
        let number = line[..<dotIndex]
        let restStart = line.index(after: dotIndex)
        guard number.allSatisfy(\.isNumber),
              restStart < line.endIndex,
              line[restStart] == " ",
              let index = Int(number) else {
            return nil
        }
        let textStart = line.index(after: restStart)
        return .ordered(index: index, text: String(line[textStart...]))
    }

    private static func table(from lines: [String], startingAt index: Int) -> (value: MarkdownTable, nextIndex: Int)? {
        guard index + 1 < lines.count else { return nil }
        let headerLine = lines[index].trimmingCharacters(in: .whitespaces)
        let separatorLine = lines[index + 1].trimmingCharacters(in: .whitespaces)
        guard isTableRow(headerLine), isTableSeparator(separatorLine) else { return nil }

        let header = splitTableRow(headerLine)
        guard header.count >= 2 else { return nil }

        var rows: [[String]] = []
        var cursor = index + 2
        while cursor < lines.count {
            let line = lines[cursor].trimmingCharacters(in: .whitespaces)
            guard isTableRow(line), !isTableSeparator(line) else { break }
            rows.append(splitTableRow(line))
            cursor += 1
        }

        guard !rows.isEmpty else { return nil }
        return (MarkdownTable(header: header, rows: rows), cursor)
    }

    private static func isTableRow(_ line: String) -> Bool {
        line.contains("|") && splitTableRow(line).count >= 2
    }

    private static func isTableSeparator(_ line: String) -> Bool {
        let cells = splitTableRow(line)
        guard cells.count >= 2 else { return false }
        return cells.allSatisfy { cell in
            let trimmed = cell.trimmingCharacters(in: .whitespaces)
            let withoutColons = trimmed.trimmingCharacters(in: CharacterSet(charactersIn: ":"))
            return withoutColons.count >= 3 && withoutColons.allSatisfy { $0 == "-" }
        }
    }

    private static func splitTableRow(_ line: String) -> [String] {
        var value = line.trimmingCharacters(in: .whitespaces)
        if value.first == "|" {
            value.removeFirst()
        }
        if value.last == "|" {
            value.removeLast()
        }
        return value
            .split(separator: "|", omittingEmptySubsequences: false)
            .map { cell in
                cell
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: "\\|", with: "|")
            }
    }
}
