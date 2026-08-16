package com.osrscopilot;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import net.runelite.client.ui.ColorScheme;

/**
 * Moves the chat UI between the sidebar and a free-floating resizable
 * frame. RuneLite's sidebar width is a fixed constant, so "make the chat
 * bigger" is only possible by carrying the content panel into its own
 * window; the conversation is untouched since the UI just moves.
 */
class PopOutManager
{
	private final JPanel host;
	private final JPanel content;
	private final JButton popOutButton;
	private final JTextField input;
	private final Theme theme;

	private JFrame frame;

	PopOutManager(JPanel host, JPanel content, JButton popOutButton, JTextField input, Theme theme)
	{
		this.host = host;
		this.content = content;
		this.popOutButton = popOutButton;
		this.input = input;
		this.theme = theme;
	}

	void toggle()
	{
		if (frame == null)
		{
			open();
		}
		else
		{
			frame.dispose();
		}
	}

	private void open()
	{
		host.remove(content);
		JLabel placeholder = new JLabel("Chat is open in its own window", JLabel.CENTER);
		placeholder.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		host.add(placeholder, BorderLayout.CENTER);
		host.revalidate();
		host.repaint();

		frame = new JFrame("OSRS Copilot");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.getContentPane().setBackground(theme.chromeBg);
		frame.add(content);
		frame.setSize(560, 720);
		frame.setLocationByPlatform(true);
		// Closing the window (by any path) docks the chat back into the
		// sidebar; the conversation is untouched since the UI just moves.
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent e)
			{
				dockBack(placeholder);
			}
		});
		popOutButton.setText("Dock");
		popOutButton.setToolTipText("Return the chat to the sidebar");
		frame.setVisible(true);
		input.requestFocusInWindow();
	}

	private void dockBack(JLabel placeholder)
	{
		frame = null;
		host.remove(placeholder);
		host.add(content, BorderLayout.CENTER);
		popOutButton.setText("Pop out");
		popOutButton.setToolTipText("Open the chat in its own resizable window");
		host.revalidate();
		host.repaint();
	}

	/** Plugin shutdown: a floating chat window must not outlive the plugin. */
	void close()
	{
		if (frame != null)
		{
			frame.dispose();
		}
	}
}
