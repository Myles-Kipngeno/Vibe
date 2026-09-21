/**
 * What the card records, and what it must not.
 *
 * These verdicts are not cosmetic: the backend reads them back into the prompt,
 * so a wrong one does not just look odd, it teaches the generator something
 * untrue about how he writes. The case that matters most is the one where
 * nothing should be recorded at all.
 */

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import SuggestionCard from "./SuggestionCard";
import { api } from "../lib/api";
import { canShareReply, sendReply } from "../lib/share";
import type { Suggestion } from "../lib/types";

vi.mock("../lib/api", () => ({
  api: { feedback: vi.fn().mockResolvedValue(undefined) },
}));

vi.mock("../lib/share", () => ({
  canShareReply: vi.fn(() => true),
  sendReply: vi.fn(async () => "shared" as const),
}));

const suggestion: Suggestion = {
  id: "sug_1",
  text: "Niaje, that lecture was rough",
  rationale: "Names something you both sat through",
  tone: "friendly",
  approach: "shared ground",
};

const feedback = vi.mocked(api.feedback);
const share = vi.mocked(sendReply);
const canShare = vi.mocked(canShareReply);

beforeEach(() => {
  feedback.mockClear().mockResolvedValue(undefined);
  share.mockClear().mockResolvedValue("shared");
  canShare.mockClear().mockReturnValue(true);
});

function verdicts() {
  return feedback.mock.calls.map(([payload]) => payload);
}

describe("sending a reply", () => {
  it("records it as used", async () => {
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
    expect(verdicts()[0]).toMatchObject({
      suggestion_id: "sug_1",
      suggestion_text: suggestion.text,
      verdict: "used",
    });
    expect(share).toHaveBeenCalledWith(suggestion.text);
  });

  it("records an edited one as edited, with the text he actually sent", async () => {
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByText(suggestion.text));
    const box = screen.getByRole("textbox");
    await userEvent.clear(box);
    await userEvent.type(box, "Niaje, that lecture was long");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
    expect(verdicts()[0]).toMatchObject({
      verdict: "edited",
      suggestion_text: "Niaje, that lecture was long",
    });
  });

  it("records nothing when he backs out of the share sheet", async () => {
    // The one that would quietly poison the feedback signal: changing his mind
    // is not a reply he sent, and counting it as one teaches the generator that
    // a line he rejected was a line he liked.
    share.mockResolvedValue("cancelled");
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    await waitFor(() => expect(share).toHaveBeenCalled());
    expect(feedback).not.toHaveBeenCalled();
  });

  it("tells him when it could not send, and records nothing", async () => {
    share.mockResolvedValue("failed");
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText(/could not send/i)).toBeInTheDocument();
    expect(feedback).not.toHaveBeenCalled();
  });

  it("says Copy where there is no share sheet", () => {
    canShare.mockReturnValue(false);
    render(<SuggestionCard suggestion={suggestion} />);
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send" })).not.toBeInTheDocument();
  });
});

describe("rejecting a reply", () => {
  it("records the rejection immediately, before asking why", async () => {
    // So that walking away never loses the verdict.
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Not me" }));

    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
    expect(verdicts()[0]).toMatchObject({ verdict: "rejected", note: "" });
    expect(screen.getByLabelText(/what was off about it/i)).toBeInTheDocument();
  });

  it("sends the reason as a second verdict for the same suggestion", async () => {
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Not me" }));
    await userEvent.type(
      screen.getByLabelText(/what was off about it/i),
      "too corny",
    );
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(2));
    const [first, second] = verdicts();
    expect(first.note).toBe("");
    expect(second).toMatchObject({
      suggestion_id: "sug_1",
      verdict: "rejected",
      note: "too corny",
    });
    // Same id both times: the backend keeps the later one rather than counting
    // this as two rejections.
    expect(second.suggestion_id).toBe(first.suggestion_id);
  });

  it("does not post again when he skips the reason", async () => {
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Not me" }));
    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByRole("button", { name: "Skip" }));

    expect(feedback).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText(/what was off about it/i)).not.toBeInTheDocument();
  });

  it("does not post an empty reason", async () => {
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Not me" }));
    await waitFor(() => expect(feedback).toHaveBeenCalledTimes(1));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(feedback).toHaveBeenCalledTimes(1);
  });
});

describe("when the backend is unreachable", () => {
  it("says so rather than pretending the verdict was saved", async () => {
    feedback.mockRejectedValue(new Error("offline"));
    render(<SuggestionCard suggestion={suggestion} />);
    await userEvent.click(screen.getByRole("button", { name: "Not me" }));

    expect(await screen.findByText(/could not save/i)).toBeInTheDocument();
  });
});
