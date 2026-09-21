/**
 * The form is where the library's rules live.
 *
 * Two of them are structural rather than cosmetic: there is nowhere to type
 * her messages, and an example cannot be saved without the situation it gets
 * matched on. A field quietly added later would undo the first; a relaxed
 * button would undo the second.
 */

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import Library from "./Library";
import { api } from "../lib/api";
import type { ConversationExample } from "../lib/types";

vi.mock("../lib/api", () => ({
  api: {
    examples: vi.fn(),
    createExample: vi.fn(),
    deleteExample: vi.fn(),
  },
}));

const listed = vi.mocked(api.examples);
const created = vi.mocked(api.createExample);
const removed = vi.mocked(api.deleteExample);

const example: ConversationExample = {
  id: "ex_1",
  situation: "Starting a new chat, opening line",
  context: "",
  opening_line: "Niaje, that lecture was rough",
  language_mix: "mixed",
  tone: "",
  reaction: "Replied within a minute",
  what_worked: "Named something we both sat through",
  what_did_not: "",
  not_suitable_when: "",
  created_at: "2026-01-01T12:00:00",
};

beforeEach(() => {
  listed.mockClear().mockResolvedValue([]);
  created.mockClear().mockResolvedValue(example);
  removed.mockClear().mockResolvedValue(undefined);
});

describe("what the form will not let you do", () => {
  it("has nowhere to put her messages", async () => {
    // Not a warning that can be ignored -- there is no field, on purpose.
    render(<Library />);
    await userEvent.click(await screen.findByRole("button", { name: /add an example/i }));

    const labels = screen
      .getAllByText(/./, { selector: "label > span:first-child" })
      .map((el) => el.textContent?.toLowerCase() ?? "");

    expect(labels.some((l) => l.includes("what you wrote"))).toBe(true);
    for (const forbidden of ["her message", "she said", "her reply", "transcript"]) {
      expect(labels.some((l) => l.includes(forbidden))).toBe(false);
    }
  });

  it("will not save an example with no situation", async () => {
    render(<Library />);
    await userEvent.click(await screen.findByRole("button", { name: /add an example/i }));

    const save = screen.getByRole("button", { name: /save example/i });
    expect(save).toBeDisabled();

    await userEvent.type(screen.getByPlaceholderText(/opening a chat/i), "   ");
    expect(save).toBeDisabled();

    await userEvent.type(screen.getByPlaceholderText(/opening a chat/i), "Opening a chat");
    expect(save).toBeEnabled();
  });

  it("asks for a description of what came back, not a quote", async () => {
    render(<Library />);
    await userEvent.click(await screen.findByRole("button", { name: /add an example/i }));
    const label = screen
      .getByText("What came back", { selector: "label > span:first-child" })
      .closest("label");
    expect(label?.textContent?.toLowerCase()).toContain("rather than quoting her");
  });
});

describe("saving", () => {
  it("sends the trimmed situation and reloads the list", async () => {
    render(<Library />);
    await userEvent.click(await screen.findByRole("button", { name: /add an example/i }));
    await userEvent.type(
      screen.getByPlaceholderText(/opening a chat/i),
      "  Opening a chat with someone from class  ",
    );
    await userEvent.click(screen.getByRole("button", { name: /save example/i }));

    await waitFor(() => expect(created).toHaveBeenCalledTimes(1));
    expect(created.mock.calls[0][0]).toMatchObject({
      situation: "Opening a chat with someone from class",
      language_mix: "mixed",
    });
    expect(listed).toHaveBeenCalledTimes(2); // on mount, and after saving
  });

  it("says so when saving fails, instead of looking like it worked", async () => {
    created.mockRejectedValue(new Error("offline"));
    render(<Library />);
    await userEvent.click(await screen.findByRole("button", { name: /add an example/i }));
    await userEvent.type(screen.getByPlaceholderText(/opening a chat/i), "Opening a chat");
    await userEvent.click(screen.getByRole("button", { name: /save example/i }));

    expect(await screen.findByText(/could not save that example/i)).toBeInTheDocument();
  });
});

describe("the list", () => {
  it("warns when an example says nothing about when it is wrong", async () => {
    listed.mockResolvedValue([example]);
    render(<Library />);

    expect(await screen.findByText(example.situation)).toBeInTheDocument();
    expect(
      screen.getByText(/have not said when this would be the wrong move/i),
    ).toBeInTheDocument();
  });

  it("shows the condition prominently when there is one", async () => {
    listed.mockResolvedValue([
      { ...example, not_suitable_when: "when she is already quiet" },
    ]);
    render(<Library />);

    expect(await screen.findByText(/not when: when she is already quiet/i)).toBeInTheDocument();
    expect(
      screen.queryByText(/have not said when this would be the wrong move/i),
    ).not.toBeInTheDocument();
  });

  it("sets expectations that the library usually declines", async () => {
    // Otherwise saving ten examples and seeing none used reads as broken.
    render(<Library />);
    const blurb = (await screen.findByText(/at most two are ever shown/i)).textContent ?? "";
    expect(blurb.toLowerCase()).toContain("boundary");
  });

  it("deletes only after confirming", async () => {
    listed.mockResolvedValue([example]);
    vi.spyOn(window, "confirm").mockReturnValue(false);
    render(<Library />);

    await userEvent.click(await screen.findByRole("button", { name: /delete/i }));
    expect(removed).not.toHaveBeenCalled();

    vi.mocked(window.confirm).mockReturnValue(true);
    await userEvent.click(screen.getByRole("button", { name: /delete/i }));
    await waitFor(() => expect(removed).toHaveBeenCalledWith("ex_1"));
  });

  it("says the library is empty rather than showing nothing", async () => {
    render(<Library />);
    expect(await screen.findByText(/nothing saved yet/i)).toBeInTheDocument();
  });
});

describe("when the library cannot be loaded", () => {
  it("says so", async () => {
    listed.mockRejectedValue(new Error("offline"));
    render(<Library />);
    expect(await screen.findByText(/could not load the library/i)).toBeInTheDocument();
  });
});

describe("accessibility of the form", () => {
  it("labels every field", async () => {
    render(<Library />);
    await userEvent.click(await screen.findByRole("button", { name: /add an example/i }));
    const form = screen.getByText("New example").closest("section");
    const inputs = within(form as HTMLElement).getAllByRole("textbox");
    expect(inputs.length).toBeGreaterThan(4);
    for (const input of inputs) {
      expect(input.closest("label")).not.toBeNull();
    }
  });
});
